package com.proovy.domain.run.contract;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** role이 user면 {@code user}만, assistant면 {@code assistant}만 있다. */
public record ConversationMessage(
        String messageId,
        MessageRole role,
        ConversationMessageStatus status,
        String runId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) UserMessageContent user,
        @JsonInclude(JsonInclude.Include.NON_NULL) AssistantMessageContent assistant
) {

    public ConversationMessage {
        createdAt = BrowserRunContract.wireTime(createdAt);
    }
}
