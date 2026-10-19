package com.proovy.domain.run.admission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proovy.domain.run.contract.BrowserRequestFingerprint;
import com.proovy.domain.run.contract.ConversationRunRequest;
import com.proovy.domain.run.contract.ConversationRunRequestValidator;
import com.proovy.global.response.ErrorCode;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 브라우저 대화 요청 접수 ({@code POST /api/conversations}, browser-run-v1). 인증은 security filter가 먼저 끝낸다(401).
 * <ol>
 *   <li>JSON tree 판정 — 구조 RUN4001, video RUN4002. DB를 보기 전이다.</li>
 *   <li>{@link RunAdmissionTransaction} — requestId replay·Note·참조·Active Run·rate limit·잔액 판정과 원자적 저장.</li>
 * </ol>
 * unique 경합(같은 requestId 동시 전송·같은 Note 동시 접수)이나 직렬화·lock·연결 실패({@link #isRetryable})로
 * transaction이 실패하면 전부 rollback된 뒤 <b>같은 body·같은 fingerprint</b>로 새 transaction을 연다. 그 시도는 먼저
 * commit된 접수의 binding을 찾아 replay하거나 RUN4091/RUN4092로 답하므로, 재시도가 잘못된 재전송을 새 run으로 바꾸지
 * 않는다. commit 결과를 모르는 연결 실패 뒤의 재시도도 binding 조회가 먼저라 run·차감이 둘이 되지 않는다. CHECK·데이터
 * 오류 같은 결정적 실패는 재시도하지 않는다.
 * <p>
 * 반환은 commit된 run 식별자다. stream 연결(seq 1 {@code run.created}부터 replay·live, 1.86)과 Brain dispatch(1.83)는
 * 이 결과를 받아 별도로 한다 — 접수 완료는 실행·terminal 완료가 아니다.
 */
@Slf4j
@Service
public class RunAdmissionService {

    /** unique_violation, serialization_failure, deadlock_detected, lock_not_available */
    private static final Set<String> RETRYABLE_SQL_STATES = Set.of("23505", "40001", "40P01", "55P03");
    private static final String CONNECTION_EXCEPTION_CLASS = "08";

    private final RunAdmissionTransaction transaction;
    private final RunAdmissionProperties properties;
    private final ObjectMapper objectMapper;

    public RunAdmissionService(RunAdmissionTransaction transaction, RunAdmissionProperties properties,
                               ObjectMapper objectMapper) {
        this.transaction = transaction;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * @param userId 인증된 사용자
     * @param body   요청 body JSON(바인딩 전 tree)
     * @throws RunAdmissionRejectedException browser-run-v1 거부(RUN4001·4002·4092·4041·4221·4091·4291·4021).
     *                                       아무것도 저장하지 않았다
     */
    public RunAdmission admit(long userId, JsonNode body) {
        Optional<ErrorCode> invalid = ConversationRunRequestValidator.validate(body);
        if (invalid.isPresent()) {
            throw RunAdmissionRejectedException.of(invalid.get());
        }
        ConversationRunRequest request = bind(body);
        BrowserRequestFingerprint fingerprint = BrowserRequestFingerprint.of(request);

        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.admit(userId, request, fingerprint);
            } catch (RunAdmissionRejectedException e) {
                throw e;
            } catch (RuntimeException e) {
                if (attempt >= properties.maxTransactions() || !isRetryable(e)) {
                    throw e;
                }
                log.info("run admission transaction {} failed, retrying with the same request binding: requestId={}, "
                        + "cause={}", attempt, request.requestId(), e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 다시 열면 결과가 달라질 수 있는 실패만 재시도한다: unique 위반(경합의 승자가 commit됨)과 직렬화·deadlock·lock 실패·연결 실패.
     * CHECK·데이터 오류처럼 결정적인 실패는 같은 body로 반복해도 같으므로 바로 던진다. 번역되지 않은 JPA flush 예외
     * ({@code PersistenceException})도 원인 사슬의 SQLState로 판정한다.
     */
    static boolean isRetryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                return RETRYABLE_SQL_STATES.contains(state) || state.startsWith(CONNECTION_EXCEPTION_CLASS);
            }
        }
        return false;
    }

    private ConversationRunRequest bind(JsonNode body) {
        try {
            return objectMapper.treeToValue(body, ConversationRunRequest.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // 판정을 통과한 body는 바인딩되어야 한다. 실패는 판정기와 DTO가 어긋난 Server 버그다.
            throw new IllegalStateException("validated conversation request could not be bound", e);
        }
    }
}
