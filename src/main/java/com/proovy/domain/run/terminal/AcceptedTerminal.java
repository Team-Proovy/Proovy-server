package com.proovy.domain.run.terminal;

/**
 * Server 기준으로 수락한 terminal — 저장 transaction(1.82)의 검증된 입력. 저장·usage 확정은 하지 않는다.
 * envelope 값은 1.82의 현재 fence 조건부 UPDATE에 그대로 쓴다.
 */
public sealed interface AcceptedTerminal permits AcceptedCompletion, AcceptedFailure {

    String runId();

    int attempt();

    String fenceToken();

    String sourceEventId();
}
