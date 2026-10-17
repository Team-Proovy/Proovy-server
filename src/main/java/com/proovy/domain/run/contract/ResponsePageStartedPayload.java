package com.proovy.domain.run.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * completed run에서만, run.completed 바로 앞에 page마다 started→completed 쌍으로 보낸다.
 *
 * @param presentation terminal-v2 PagePresentation wire 값
 * @param title        없으면 생략
 */
public record ResponsePageStartedPayload(
        String assistantMessageId,
        String responsePageId,
        String presentation,
        @JsonInclude(JsonInclude.Include.NON_NULL) String title,
        int pageIndex
) {
}
