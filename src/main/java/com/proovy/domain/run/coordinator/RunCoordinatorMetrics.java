package com.proovy.domain.run.coordinator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;

/**
 * coordinator 관측 지표(Micrometer). tag 값은 고정 enum 이름뿐이다 — run·request ID·payload를 label로 쓰지 않는다.
 * <ul>
 *   <li>{@code proovy.run.coordinator.slots.in_use}·{@code .slots.max}·{@code .leader}(gauge)</li>
 *   <li>{@code proovy.run.coordinator.claims}: claim한 run</li>
 *   <li>{@code proovy.run.coordinator.redeliveries{kind}}: 같은 attempt 재전달 원인(BrainFailureKind 또는 status state)</li>
 *   <li>{@code proovy.run.coordinator.status_checks{result}}: status 조회 결과</li>
 *   <li>{@code proovy.run.coordinator.executions{result}}: worker 종료 사유({@link RunExecution.Result})</li>
 *   <li>{@code proovy.run.coordinator.server_failures{code}}: coordinator가 확정한 Server 실패</li>
 *   <li>{@code proovy.run.coordinator.deadline_expired}·{@code .heartbeat.lost}·{@code .errors{stage}}</li>
 * </ul>
 */
class RunCoordinatorMetrics {

    private static final String PREFIX = "proovy.run.coordinator.";

    private final MeterRegistry registry;

    RunCoordinatorMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void gauges(Supplier<Number> inUse, int max, Supplier<Number> leader) {
        Gauge.builder(PREFIX + "slots.in_use", inUse).register(registry);
        Gauge.builder(PREFIX + "slots.max", () -> max).register(registry);
        Gauge.builder(PREFIX + "leader", leader).register(registry);
    }

    void claimed() {
        counter("claims").increment();
    }

    void redelivery(String kind) {
        counter("redeliveries", "kind", kind).increment();
    }

    void statusCheck(String result) {
        counter("status_checks", "result", result).increment();
    }

    void execution(RunExecution.Result result) {
        counter("executions", "result", result.name()).increment();
    }

    void serverFailure(String code) {
        counter("server_failures", "code", code).increment();
    }

    void deadlineExpired() {
        counter("deadline_expired").increment();
    }

    void heartbeatLost() {
        counter("heartbeat.lost").increment();
    }

    void error(String stage) {
        counter("errors", "stage", stage).increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(PREFIX + name).tags(tags).register(registry);
    }
}
