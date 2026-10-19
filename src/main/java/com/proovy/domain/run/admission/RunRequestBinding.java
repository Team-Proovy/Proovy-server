package com.proovy.domain.run.admission;

import com.proovy.global.entity.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 브라우저 requestId → run binding (PK = request_id). 접수 시 browser-run-v1 요청 fingerprint를 고정해
 * 같은 requestId 재전송이 같은 사용자·같은 의미인지 판정한다. insert 뒤 바꾸지 않는다(DB trigger).
 */
@Entity
@Table(name = "run_request_bindings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunRequestBinding extends AssignedIdEntity<String> {

    @Id
    @Column(name = "request_id", length = 128)
    private String requestId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "fingerprint_algorithm", nullable = false, length = 64, updatable = false)
    private String fingerprintAlgorithm;

    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    RunRequestBinding(String requestId, Long userId, UUID runId, String fingerprintAlgorithm,
                      String requestFingerprint, Instant createdAt) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.fingerprintAlgorithm = Objects.requireNonNull(fingerprintAlgorithm, "fingerprintAlgorithm");
        this.requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    @Override
    public String getId() {
        return requestId;
    }

    /** 같은 사용자·같은 fingerprint 알고리즘·같은 의미면 기존 run의 replay다. */
    boolean matches(long userId, String algorithm, String fingerprint) {
        return this.userId == userId && fingerprintAlgorithm.equals(algorithm)
                && requestFingerprint.equals(fingerprint);
    }
}
