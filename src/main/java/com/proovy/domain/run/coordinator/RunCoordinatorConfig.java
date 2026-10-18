package com.proovy.domain.run.coordinator;

import com.proovy.domain.run.event.RunEventStore;
import com.proovy.domain.run.repository.AiRunRepository;
import com.proovy.domain.run.snapshot.RunAssetAccess;
import com.proovy.domain.run.terminal.RunTerminalService;
import com.proovy.global.infra.brain.BrainRunClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code proovy.run.coordinator.enabled=true}일 때만 coordinator를 만든다. 켜졌는데 Brain client가 없으면 기동을 실패시킨다
 * (접수된 run이 조용히 PENDING에 머무르지 않게). 다중 인스턴스에서도 coordinator lease 보유자 하나만 claim한다.
 */
@Configuration
@ConditionalOnProperty(prefix = "proovy.run.coordinator", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RunCoordinatorProperties.class)
public class RunCoordinatorConfig {

    @Bean
    public RunClaimStore runCoordinatorClaimStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new RunClaimStore(jdbc, tx);
    }

    /** S09/S10 전 기본값: grant·read URL 없음(run-snapshot-v1 R7). 발급처가 생기면 이 bean을 교체한다. */
    @Bean
    @ConditionalOnMissingBean
    public RunAssetAccessProvider runCoordinatorAssetAccess() {
        return (run, claim) -> RunAssetAccess.NONE;
    }

    @Bean
    public RunCoordinator runCoordinator(RunCoordinatorProperties properties, RunClaimStore claims,
            ObjectProvider<BrainRunClient> brain, RunEventStore events, RunTerminalService terminals,
            AiRunRepository runs, RunAssetAccessProvider access, ObjectProvider<MeterRegistry> registry) {
        BrainRunClient client = brain.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(
                    "proovy.run.coordinator.enabled=true requires the Brain client (proovy.brain.base-url)");
        }
        RunCoordinatorMetrics metrics = new RunCoordinatorMetrics(registry.getIfAvailable(SimpleMeterRegistry::new));
        String owner = properties.instanceId() == null || properties.instanceId().isBlank()
                ? defaultInstanceId() : properties.instanceId();
        RunExecution.Dependencies dependencies = new RunExecution.Dependencies(client, events, terminals, claims, runs,
                access, properties, metrics, owner, Clock.systemUTC());
        return new RunCoordinator(properties, claims, terminals, dependencies, metrics);
    }

    /** host 이름(허용 문자만, 60자) + 무작위 접미사. 재시작마다 달라 이전 프로세스의 lease와 구별된다. */
    static String defaultInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "server";
        }
        host = host.replaceAll("[^A-Za-z0-9_.:-]", "-").replaceAll("^[^A-Za-z0-9]+", "");
        if (host.isEmpty()) {
            host = "server";
        }
        if (host.length() > 60) {
            host = host.substring(0, 60);
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
