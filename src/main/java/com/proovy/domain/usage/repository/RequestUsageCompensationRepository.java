package com.proovy.domain.usage.repository;

import com.proovy.domain.usage.entity.RequestUsageCompensation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestUsageCompensationRepository extends JpaRepository<RequestUsageCompensation, String> {
}
