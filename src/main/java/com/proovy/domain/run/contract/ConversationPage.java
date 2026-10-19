package com.proovy.domain.run.contract;

import java.util.List;

/**
 * GET /api/conversations/{noteId}/messages?before&amp;limit result.
 * messages는 createdAt 오름차순, 최대 100개.
 *
 * @param nextBefore 더 오래된 메시지를 읽을 다음 before 값, 없으면 null
 */
public record ConversationPage(
        String noteId,
        ActiveRunSummary activeRun,
        List<ConversationMessage> messages,
        String nextBefore
) {
}
