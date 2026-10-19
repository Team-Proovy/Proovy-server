package com.proovy.domain.run.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * POST /api/conversations body. closed: 알 수 없는/금지 필드는 RUN4001
 * ({@link ConversationRunRequestValidator}). 브라우저는 scope·usage·attempt·fence·history·
 * 참조를 결정하지 않으므로 해당 필드가 없다.
 *
 * @param requestId         클라이언트가 만든 소문자 UUID. 같은 의미의 재전송은 기존 run을 replay한다.
 * @param noteId            없으면 Server가 Note를 만든다. 재전송은 처음 보낸 body 그대로(noteId를 추가하면 다른 의미).
 * @param requestedTasks    빈 배열·부재는 Brain이 문장에서 추론한다.
 * @param requestedOutputs  빈 배열·부재는 추론. video는 RUN4002.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationRunRequest(
        String requestId,
        String noteId,
        String text,
        String latex,
        List<RequestedTask> requestedTasks,
        List<RequestedOutput> requestedOutputs,
        List<String> turnAttachmentIds,
        ViewerFocus viewerFocus
) {
}
