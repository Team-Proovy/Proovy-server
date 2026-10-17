package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.LOCALE;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_REQUESTED_VALUES;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_REQUEST_LATEX_LENGTH;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_REQUEST_TEXT_LENGTH;
import static com.proovy.domain.run.brain.InternalRunContract.list;
import static com.proovy.domain.run.brain.InternalRunContract.matching;
import static com.proovy.domain.run.brain.InternalRunContract.optionalText;
import static com.proovy.domain.run.brain.InternalRunContract.unique;

import com.proovy.domain.run.contract.RequestedOutput;
import com.proovy.domain.run.contract.RequestedTask;
import java.util.List;
import java.util.function.Function;

/**
 * 이번 turn의 의미 입력. requested 배열은 Server가 정규화한 값이고(중복 없음), Brain은 기본값을 채우지 않는다.
 */
public record RunInput(
        String text,
        String latex,
        List<RequestedTask> requestedTasks,
        List<RequestedOutput> requestedOutputs,
        String locale) {

    public RunInput {
        text = InternalRunContract.text(text, "input.text", 1, MAX_REQUEST_TEXT_LENGTH);
        latex = optionalText(latex, "input.latex", 1, MAX_REQUEST_LATEX_LENGTH);
        requestedTasks = list(requestedTasks, MAX_REQUESTED_VALUES, "input.requested_tasks");
        requestedOutputs = list(requestedOutputs, MAX_REQUESTED_VALUES, "input.requested_outputs");
        unique(requestedTasks, Function.identity(), "input.requested_tasks");
        unique(requestedOutputs, Function.identity(), "input.requested_outputs");
        locale = matching(locale, "input.locale", LOCALE, 35);
    }
}
