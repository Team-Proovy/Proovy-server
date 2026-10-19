package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.MAX_HISTORY_MESSAGE_CHARS;
import static com.proovy.domain.run.brain.InternalRunContract.opaqueId;
import static com.proovy.domain.run.brain.InternalRunContract.require;

/** 이전 turn 하나의 공개 plain text. Server가 공개 Result Block에서 만들고 시간 순서를 지킨다. */
public record HistoryMessage(String messageId, HistoryRole role, String text) {

    public HistoryMessage {
        messageId = opaqueId(messageId, "history.message_id");
        require(role, "history.role");
        text = InternalRunContract.text(text, "history.text", 1, MAX_HISTORY_MESSAGE_CHARS);
    }
}
