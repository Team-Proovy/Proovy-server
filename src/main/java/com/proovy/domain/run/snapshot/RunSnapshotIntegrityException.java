package com.proovy.domain.run.snapshot;

/**
 * 저장된 snapshot으로 원래 요청을 복원할 수 없다(문서 손상, fingerprint·deadline 불일치, 전달 자격증명 누락).
 * 현재 설정으로 다시 만들지 않는다 — 호출자(coordinator)는 이 run을 실패로 수습한다. message에 값은 넣지 않는다.
 */
public class RunSnapshotIntegrityException extends IllegalStateException {

    RunSnapshotIntegrityException(String message) {
        super(message);
    }
}
