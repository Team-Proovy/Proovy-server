package com.proovy.domain.usage.service;

import com.proovy.domain.usage.entity.RequestUsageCharge;

/**
 * @param newlyCharged 이번 호출이 차감했으면 true, 같은 request의 기존 차감을 돌려주면 false
 * @param charge       request의 차감 row (새 row 또는 기존 row)
 */
public record UsageChargeResult(boolean newlyCharged, RequestUsageCharge charge) {
}
