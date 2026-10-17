package com.proovy.global.infra.brain;

/**
 * Brain 내부 API의 서비스 인증. 모드는 하나만 켠다(dual-auth·fallback 없음).
 * 사용자 JWT·브라우저 header는 이 경계로 들어오지 않는다.
 */
public interface BrainServiceCredentials {

    /** Authorization Bearer 값. 실패하면 값 없는 예외를 던진다. */
    String bearerToken();
}
