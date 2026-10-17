package com.proovy.domain.run.snapshot;

import static com.proovy.domain.run.brain.InternalRunContract.MAX_HISTORY_MESSAGES;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_HISTORY_MESSAGE_CHARS;

import com.proovy.domain.run.brain.HistoryMessage;
import com.proovy.domain.run.brain.HistoryRole;
import com.proovy.domain.run.contract.BrowserRunContract;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 공개 history를 최신 메시지부터 token 상한 안으로 자른다.
 * <ul>
 *   <li>추정은 Brain {@code estimate_history_turn_tokens}와 같다: strip한 본문의 code point 수 + 메시지당 8.</li>
 *   <li>메시지 중간을 자르지 않는다. 최신부터 연속으로 담고, 다음(더 오래된) 메시지가 넘치면 거기서 멈춘다.
 *       Brain과 달리 최신 메시지 하나도 상한을 넘으면 담지 않는다 — Server가 보내는 history는 항상 상한 이하다
 *       (Brain은 같은 추정으로 다시 잘라도 아무것도 버리지 않는다).</li>
 *   <li>wire 상한(메시지 100개, 메시지당 50,000자)도 같은 방식으로 멈춘다.</li>
 *   <li>공백만인 본문은 공개 메시지가 아니므로 건너뛴다. 현재 입력과 같은 user 본문은 넣지 않는다(Brain이 거부).</li>
 * </ul>
 */
public final class HistoryBound {

    /** Brain {@code _HISTORY_TURN_OVERHEAD}. */
    public static final int MESSAGE_OVERHEAD_TOKENS = 8;

    private HistoryBound() {
    }

    /** history 후보 하나. {@code plainText}는 공개 본문에서 파생한 값이다. */
    public record Candidate(UUID messageId, HistoryRole role, String plainText) {
    }

    /**
     * @param messages        시간순 history(오래된 것부터)
     * @param estimatedTokens 담은 메시지의 추정 token 합
     * @param truncated       더 오래된 공개 메시지를 상한 때문에 버렸는지
     */
    public record Result(List<HistoryMessage> messages, int estimatedTokens, boolean truncated) {
    }

    public static int estimateTokens(String strippedText) {
        return BrowserRunContract.codePointLength(strippedText) + MESSAGE_OVERHEAD_TOKENS;
    }

    /**
     * @param newestFirst 최신 메시지부터의 후보
     * @param maxTokens   추정 token 상한
     * @param currentText 현재 요청의 input text(strip 전이어도 된다)
     */
    public static Result keepNewest(List<Candidate> newestFirst, int maxTokens, String currentText) {
        String current = BrowserRunContract.strip(currentText);
        List<HistoryMessage> kept = new ArrayList<>();
        int used = 0;
        boolean truncated = false;
        for (Candidate candidate : newestFirst) {
            String text = candidate.plainText() == null ? "" : BrowserRunContract.strip(candidate.plainText());
            if (text.isEmpty()) {
                continue;
            }
            if (candidate.role() == HistoryRole.USER && text.equals(current)) {
                continue;
            }
            int estimate = estimateTokens(text);
            if (kept.size() == MAX_HISTORY_MESSAGES
                    || BrowserRunContract.codePointLength(text) > MAX_HISTORY_MESSAGE_CHARS
                    || used + estimate > maxTokens) {
                truncated = true;
                break;
            }
            kept.add(new HistoryMessage(candidate.messageId().toString(), candidate.role(), text));
            used += estimate;
        }
        Collections.reverse(kept);
        return new Result(List.copyOf(kept), used, truncated);
    }
}
