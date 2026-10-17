package com.proovy.domain.run.brain;

/**
 * internal-run-v1 계약 위반. message는 필드 이름과 위반 종류만 담고 입력 값(grant·URL·본문)은 담지 않는다.
 */
public class InternalRunContractViolation extends IllegalArgumentException {

    public InternalRunContractViolation(String message) {
        super(message);
    }
}
