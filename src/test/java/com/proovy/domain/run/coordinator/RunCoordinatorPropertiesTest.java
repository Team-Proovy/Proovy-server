package com.proovy.domain.run.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** {@code proovy.run.coordinator.*} 기동 검사: 전역 슬롯 ≤ Brain 배포 상한, lease·heartbeat 관계, 재전달 한도. */
class RunCoordinatorPropertiesTest {

    @Test
    void defaultsMatchPlanStartingValues() {
        RunCoordinatorProperties properties = bind(Map.of());
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.maxConcurrentRuns()).isEqualTo(4);
        assertThat(properties.brainCapacity().total()).isEqualTo(12);
        assertThat(properties.heartbeatInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.leaseTtl()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void globalSlotsMustNotExceedBrainDeploymentCapacity() {
        assertThatThrownBy(() -> bind(Map.of(
                "proovy.run.coordinator.max-concurrent-runs", "5",
                "proovy.run.coordinator.brain-capacity.instances", "1",
                "proovy.run.coordinator.brain-capacity.per-instance", "4")))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("must not exceed brain-capacity");
        assertThat(bind(Map.of("proovy.run.coordinator.max-concurrent-runs", "12")).maxConcurrentRuns())
                .isEqualTo(12);
    }

    @Test
    void leaseMustOutliveSeveralHeartbeats() {
        assertThatThrownBy(() -> bind(Map.of(
                "proovy.run.coordinator.heartbeat-interval", "15s",
                "proovy.run.coordinator.lease-ttl", "30s")))
                .rootCause().hasMessageContaining("lease-ttl");
    }

    @Test
    void redeliveryIsBoundedWithCappedExponentialBackoff() {
        RunCoordinatorProperties.Redelivery redelivery = bind(Map.of()).redelivery();
        assertThat(redelivery.maxResends()).isEqualTo(6);
        assertThat(redelivery.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(redelivery.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(redelivery.backoff(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(redelivery.backoff(10)).isEqualTo(Duration.ofSeconds(15));
        assertThatThrownBy(() -> bind(Map.of("proovy.run.coordinator.redelivery.max-resends", "1000")))
                .rootCause().hasMessageContaining("max-resends");
    }

    @Test
    void recoveryIsPeriodicAndBoundedPerRun() {
        RunCoordinatorProperties.Recovery recovery = bind(Map.of()).recovery();
        assertThat(recovery.interval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(recovery.maxRecoveries()).isEqualTo(3);
        assertThat(recovery.batch()).isEqualTo(8);
        assertThatThrownBy(() -> bind(Map.of("proovy.run.coordinator.recovery.max-recoveries", "0")))
                .rootCause().hasMessageContaining("max-recoveries");
        assertThatThrownBy(() -> bind(Map.of("proovy.run.coordinator.recovery.interval", "0s")))
                .rootCause().hasMessageContaining("recovery.interval");
    }

    @Test
    void instanceIdIsRestrictedToLeaseOwnerCharacters() {
        assertThatThrownBy(() -> bind(Map.of("proovy.run.coordinator.instance-id", "bad owner")))
                .rootCause().hasMessageContaining("instance-id");
        assertThat(RunCoordinatorProperties.INSTANCE_ID.matcher(RunCoordinatorConfig.defaultInstanceId()).matches())
                .isTrue();
    }

    private static RunCoordinatorProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("proovy.run.coordinator", RunCoordinatorProperties.class);
    }
}
