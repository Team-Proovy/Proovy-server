package com.proovy.domain.run.terminal;

import com.proovy.domain.run.entity.RunFailureClass;
import com.proovy.domain.run.entity.RunFailureCode;

/**
 * 수락한 {@code run.failed}. code·class·reason·고정 안전 message가 서로 맞는 것만 수락한다(답 본문 없음).
 *
 * @param reason invalid_attachment일 때만 있다
 */
public record AcceptedFailure(
        String runId,
        int attempt,
        String fenceToken,
        String sourceEventId,
        RunFailureCode code,
        RunFailureClass failureClass,
        AttachmentFailureReason reason,
        String message,
        boolean recoverable) implements AcceptedTerminal {
}
