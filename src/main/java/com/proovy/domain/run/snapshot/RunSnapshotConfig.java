package com.proovy.domain.run.snapshot;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RunSnapshotProperties.class)
public class RunSnapshotConfig {
}
