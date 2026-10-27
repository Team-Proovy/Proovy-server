package com.proovy.domain.run.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.proovy.domain.run.brain.ExecutionLimits;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** {@code proovy.run.snapshot.budget.*} 기본값: 설정이 없을 때 새 run snapshot에 고정되는 실행 상한. */
class RunSnapshotPropertiesTest {

    @Test
    void defaultBudgetAllowsEightyLlmCallsAndKeepsTheOtherLimits() {
        RunSnapshotProperties properties = bind(Map.of());
        // verify 라운드 상한이 (run 남은 호출) // 3이라 40이면 12회로 잘린다(task 1.106, 2026-10-24·25 재실측).
        assertThat(properties.budget().policyVersion()).isEqualTo("budget-v1");
        assertThat(properties.budget().limits())
                .isEqualTo(new ExecutionLimits(80, 40, 100_000, 5_000_000, 5, 10, 50));
    }

    @Test
    void configuredLlmCallLimitOverridesTheDefault() {
        RunSnapshotProperties properties = bind(Map.of("proovy.run.snapshot.budget.max-llm-calls", "40"));
        assertThat(properties.budget().limits().maxLlmCalls()).isEqualTo(40);
    }

    private static RunSnapshotProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("proovy.run.snapshot", RunSnapshotProperties.class);
    }
}
