package com.proovy.domain.run.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.proovy.domain.run.brain.HistoryMessage;
import com.proovy.domain.run.brain.HistoryRole;
import com.proovy.domain.run.snapshot.HistoryBound.Candidate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** history token 경계: 최신 메시지부터, 메시지 단위로, 추정 합이 상한 이하. */
class HistoryBoundTest {

    private static final int MAX = 24_000;

    private static Candidate user(String text) {
        return new Candidate(UUID.randomUUID(), HistoryRole.USER, text);
    }

    private static Candidate assistant(String text) {
        return new Candidate(UUID.randomUUID(), HistoryRole.ASSISTANT, text);
    }

    /** 추정 token이 정확히 {@code tokens}인 본문(code point + 8). */
    private static String sized(int tokens) {
        return "가".repeat(tokens - HistoryBound.MESSAGE_OVERHEAD_TOKENS);
    }

    @Test
    void keepsChronologicalOrderOfTheNewestMessages() {
        Candidate a2 = assistant("두 번째 답");
        Candidate u2 = user("두 번째 질문");
        Candidate a1 = assistant("첫 답");
        Candidate u1 = user("첫 질문");

        HistoryBound.Result result = HistoryBound.keepNewest(List.of(a2, u2, a1, u1), MAX, "세 번째 질문");

        assertThat(result.messages()).extracting(HistoryMessage::text)
                .containsExactly("첫 질문", "첫 답", "두 번째 질문", "두 번째 답");
        assertThat(result.messages()).extracting(HistoryMessage::role)
                .containsExactly(HistoryRole.USER, HistoryRole.ASSISTANT, HistoryRole.USER, HistoryRole.ASSISTANT);
        assertThat(result.messages().get(0).messageId()).isEqualTo(u1.messageId().toString());
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void exactlyAtTheBoundKeepsEverythingAndOneMoreTokenDropsTheOldestWholeMessage() {
        Candidate newest = assistant(sized(12_000));
        Candidate older = user(sized(12_000));

        HistoryBound.Result atBound = HistoryBound.keepNewest(List.of(newest, older), MAX, "다음 질문");
        assertThat(atBound.messages()).hasSize(2);
        assertThat(atBound.estimatedTokens()).isEqualTo(MAX);
        assertThat(atBound.truncated()).isFalse();

        Candidate olderByOne = user(sized(12_001));
        HistoryBound.Result over = HistoryBound.keepNewest(List.of(newest, olderByOne), MAX, "다음 질문");
        assertThat(over.messages()).extracting(HistoryMessage::messageId)
                .containsExactly(newest.messageId().toString());
        // 남은 메시지는 원문 그대로다(중간 절단 없음)
        assertThat(over.messages().get(0).text()).isEqualTo(newest.plainText());
        assertThat(over.estimatedTokens()).isEqualTo(12_000);
        assertThat(over.truncated()).isTrue();
    }

    @Test
    void stopsAtTheFirstOverflowSoTheKeptHistoryIsAContiguousNewestSuffix() {
        Candidate newest = assistant(sized(20_000));
        Candidate tooBig = user(sized(5_000));
        Candidate tinyOlder = assistant("짧은 옛 답");

        HistoryBound.Result result = HistoryBound.keepNewest(List.of(newest, tooBig, tinyOlder), MAX, "q");

        // 작은 옛 메시지가 남은 예산에 들어가도 건너뛰어 담지 않는다
        assertThat(result.messages()).extracting(HistoryMessage::messageId)
                .containsExactly(newest.messageId().toString());
    }

    @Test
    void theNewestMessageIsKeptWholeEvenAboveTheBoundAndEverythingOlderIsDropped() {
        Candidate longAnswer = assistant(sized(MAX + 1));
        HistoryBound.Result result = HistoryBound.keepNewest(List.of(longAnswer, user("옛 질문")), MAX, "q");

        // Brain bound_history와 같은 soft 상한: 최신 하나는 통째로, 그 뒤는 상한 이하일 때만
        assertThat(result.messages()).extracting(HistoryMessage::messageId)
                .containsExactly(longAnswer.messageId().toString());
        assertThat(result.messages().get(0).text()).isEqualTo(longAnswer.plainText());
        assertThat(result.estimatedTokens()).isEqualTo(MAX + 1);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void aSingleNewestMessageWithinTheBoundStillLeavesRoomOnlyUpToTheBound() {
        Candidate newest = assistant(sized(MAX));
        HistoryBound.Result result = HistoryBound.keepNewest(List.of(newest, user("q0")), MAX, "q");
        assertThat(result.messages()).hasSize(1);
        assertThat(result.estimatedTokens()).isEqualTo(MAX);
    }

    @Test
    void countsCodePointsNotUtf16Units() {
        String astral = "𝑥".repeat(100); // 100 code points, 200 UTF-16 units
        assertThat(HistoryBound.estimateTokens(astral)).isEqualTo(108);
        HistoryBound.Result result = HistoryBound.keepNewest(List.of(user(astral)), 108, "q");
        assertThat(result.messages()).hasSize(1);
    }

    @Test
    void doesNotRepeatTheCurrentInputAsAUserMessage() {
        Candidate sameAsInput = user("  더 자세히 설명해줘 ");
        Candidate answer = assistant("자세한 설명");
        Candidate assistantSameText = assistant("더 자세히 설명해줘");

        HistoryBound.Result result = HistoryBound.keepNewest(
                List.of(assistantSameText, answer, sameAsInput), MAX, "더 자세히 설명해줘");

        assertThat(result.messages()).extracting(HistoryMessage::messageId)
                .containsExactly(answer.messageId().toString(), assistantSameText.messageId().toString());
    }

    @Test
    void blankPublicTextIsNotAHistoryMessage() {
        Candidate blank = assistant(" \n ");
        Candidate missing = assistant(null);
        Candidate question = user("질문");

        HistoryBound.Result result = HistoryBound.keepNewest(List.of(blank, missing, question), MAX, "q");

        assertThat(result.messages()).extracting(HistoryMessage::text).containsExactly("질문");
    }

    @Test
    void wireCeilingsAlsoStopAtAMessageBoundary() {
        List<Candidate> many = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            many.add(user("m" + i));
        }
        HistoryBound.Result result = HistoryBound.keepNewest(many, MAX, "q");
        assertThat(result.messages()).hasSize(100);
        assertThat(result.messages().get(99).text()).isEqualTo("m0");
        assertThat(result.messages().get(0).text()).isEqualTo("m99");
        assertThat(result.truncated()).isTrue();

        // wire 메시지당 50,000자는 hard: soft 예외인 최신 메시지라도 넘으면 담지 않고 멈춘다
        HistoryBound.Result huge = HistoryBound.keepNewest(
                List.of(assistant("가".repeat(50_001)), user("옛 질문")), MAX, "q");
        assertThat(huge.messages()).isEmpty();
        assertThat(huge.truncated()).isTrue();
        HistoryBound.Result atWireLimit = HistoryBound.keepNewest(List.of(assistant("가".repeat(50_000))), MAX, "q");
        assertThat(atWireLimit.messages()).hasSize(1);
    }
}
