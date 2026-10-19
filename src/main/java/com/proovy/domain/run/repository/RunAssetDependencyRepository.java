package com.proovy.domain.run.repository;

import com.proovy.domain.run.entity.RunAssetDependency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RunAssetDependencyRepository extends JpaRepository<RunAssetDependency, RunAssetDependency.Key> {

    List<RunAssetDependency> findByRunId(UUID runId);

    List<RunAssetDependency> findByAssetId(Long assetId);
}
