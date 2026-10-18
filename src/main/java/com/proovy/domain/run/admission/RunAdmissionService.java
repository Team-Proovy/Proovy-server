package com.proovy.domain.run.admission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proovy.domain.run.contract.BrowserRequestFingerprint;
import com.proovy.domain.run.contract.ConversationRunRequest;
import com.proovy.domain.run.contract.ConversationRunRequestValidator;
import com.proovy.global.response.ErrorCode;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * 브라우저 대화 요청 접수 ({@code POST /api/conversations}, browser-run-v1). 인증은 security filter가 먼저 끝낸다(401).
 * <ol>
 *   <li>JSON tree 판정 — 구조 RUN4001, video RUN4002. DB를 보기 전이다.</li>
 *   <li>{@link RunAdmissionTransaction} — requestId replay·Note·참조·Active Run·rate limit·잔액 판정과 원자적 저장.</li>
 * </ol>
 * unique 경합(같은 requestId 동시 전송·같은 Note 동시 접수)이나 lock·연결 실패로 transaction이 실패하면 전부 rollback된
 * 뒤 <b>같은 body·같은 fingerprint</b>로 새 transaction을 연다. 그 시도는 먼저 commit된 접수의 binding을 찾아 replay하거나
 * RUN4091/RUN4092로 답하므로, 재시도가 잘못된 재전송을 새 run으로 바꾸지 않는다. commit 결과를 모르는 실패(timeout 등)
 * 뒤의 재시도도 binding 조회가 먼저라 run·차감이 둘이 되지 않는다.
 * <p>
 * 반환은 commit된 run 식별자다. stream 연결(seq 1 {@code run.created}부터 replay·live, 1.86)과 Brain dispatch(1.83)는
 * 이 결과를 받아 별도로 한다 — 접수 완료는 실행·terminal 완료가 아니다.
 */
@Slf4j
@Service
public class RunAdmissionService {

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
            } catch (DataAccessException | TransactionException e) {
                if (attempt >= properties.maxTransactions()) {
                    throw e;
                }
                log.info("run admission transaction {} failed, retrying with the same request binding: requestId={}, "
                        + "cause={}", attempt, request.requestId(), e.getClass().getSimpleName());
            }
        }
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
