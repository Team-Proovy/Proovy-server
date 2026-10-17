package com.proovy.domain.usage.repository;

import com.proovy.domain.usage.entity.RequestUsageCharge;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestUsageChargeRepository extends JpaRepository<RequestUsageCharge, String> {
}
