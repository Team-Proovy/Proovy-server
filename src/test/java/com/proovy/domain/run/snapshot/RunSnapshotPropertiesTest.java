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
    void defaultBudgetAllowsTwentyLlmCallsAndKeepsTheOtherLimits() {
        RunSnapshotProperties properties = bind(Map.of());
        assertThat(properties.budget().policyVersion()).isEqualTo("budget-v2");
        assertThat(properties.budget().limits())
                .isEqualTo(new ExecutionLimits(20, 40, 100_000, 5_000_000, 5, 10, 50));
    }

    @Test
    void configuredLlmCallLimitOverridesTheDefault() {
        RunSnapshotProperties properties = bind(Map.of("proovy.run.snapshot.budget.max-llm-calls", "40"));
        assertThat(properties.budget().limits().maxLlmCalls()).isEqualTo(40);
    }

    @Test
    void defaultAssetVersionsMatchBrainNormalizationAndPageUnderstandingVersions() {
        RunSnapshotProperties properties = bind(Map.of());
        assertThat(properties.assetVersions().uploadPolicyVersion()).isEqualTo("upload-v1");
        assertThat(properties.assetVersions().pageUnderstandingVersion()).isEqualTo("page-understanding-v7");
        assertThat(properties.assetVersions().imageNormalizationVersion()).isEqualTo("image-normalization-v1");
    }

    private static RunSnapshotProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("proovy.run.snapshot", RunSnapshotProperties.class);
    }
}
