package com.proovy.domain.run.event;

/**
 * Brain source Activity가 activity-v1 계약을 어겼다. message는 필드 위치와 위반 종류만 담고
 * 입력 값(후보 본문·code·stdout·알 수 없는 key 이름)은 담지 않는다.
 */
public class ActivityContractViolation extends IllegalArgumentException {

    public ActivityContractViolation(String message) {
        super(message);
    }
}
