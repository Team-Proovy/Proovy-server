package com.proovy.domain.user.dto.response;

import com.proovy.domain.user.entity.PlanType;
import com.proovy.domain.user.entity.UserPlan;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Builder
public record SubscriptionResponse(
        CurrentPlanDto currentPlan,
        PeriodDto period,
        BenefitsDto benefits,
        BillingDto billing,
        List<AvailablePlanDto> availablePlans
) {
    @Builder
    public record CurrentPlanDto(
            String name,
            String displayName,
            Integer price,
            String currency,
            String billingCycle
    ) {}

    @Builder
    public record PeriodDto(
            String startDate,
            String endDate,
            Integer daysRemaining
    ) {}

    @Builder
    public record BenefitsDto(
            Integer dailyCredit,
            Integer monthlyCredit,
            Integer maxMonthlyCredit,
            String storageLimit,
            String maxFileSize,
            Integer maxNotes
    ) {}

    @Builder
    public record BillingDto(
            String nextBillingDate,
            Boolean autoRenew
    ) {}

    @Builder
    public record AvailablePlanDto(
            String name,
            String displayName,
            Integer price,
            String currency,
            String billingCycle,
            BenefitsDto benefits
    ) {}

    public static SubscriptionResponse from(UserPlan userPlan) {
        PlanType planType = userPlan.getPlanType();

        return SubscriptionResponse.builder()
                .currentPlan(buildCurrentPlan(planType))
                .period(buildPeriod(userPlan))
                .benefits(buildBenefits(planType))
                .billing(buildBilling(userPlan))
                .availablePlans(buildAvailablePlans(planType))
                .build();
    }

    private static CurrentPlanDto buildCurrentPlan(PlanType planType) {
        return CurrentPlanDto.builder()
                .name(planType.name().toLowerCase())
                .displayName(planType.getDisplayName())
                .price(planType.getPrice())
                .currency(planType.getCurrency())
                .billingCycle(planType.getBillingCycle())
                .build();
    }

    private static PeriodDto buildPeriod(UserPlan userPlan) {
        if (userPlan.getPlanType() == PlanType.FREE) {
            return null;
        }

        LocalDateTime startedAt = userPlan.getStartedAt();
        LocalDateTime expiredAt = userPlan.getExpiredAt();

        Integer daysRemaining = expiredAt != null
                ? (int) ChronoUnit.DAYS.between(LocalDate.now(), expiredAt.toLocalDate())
                : null;

        return PeriodDto.builder()
                .startDate(startedAt != null
                        ? startedAt.format(DateTimeFormatter.ISO_LOCAL_DATE) : null)
                .endDate(expiredAt != null
                        ? expiredAt.format(DateTimeFormatter.ISO_LOCAL_DATE) : null)
                .daysRemaining(daysRemaining)
                .build();
    }

    private static BenefitsDto buildBenefits(PlanType planType) {
        return BenefitsDto.builder()
                .dailyCredit(planType.getDailyCreditLimit())
                .monthlyCredit(planType.getMonthlyCreditLimit())
                .maxMonthlyCredit(planType.getMonthlyCreditLimit())
                .storageLimit(planType.getStorageLimitGb() + "GB")
                .maxFileSize(planType.getSingleFileLimitMb() + "MB")
                .maxNotes(planType.getNoteLimit())
                .build();
    }

    private static BillingDto buildBilling(UserPlan userPlan) {
        if (userPlan.getPlanType() == PlanType.FREE) {
            return null;
        }

        LocalDateTime expiredAt = userPlan.getExpiredAt();

        return BillingDto.builder()
                .nextBillingDate(expiredAt != null
                        ? expiredAt.format(DateTimeFormatter.ISO_LOCAL_DATE) : null)
                .autoRenew(userPlan.getIsActive())
                .build();
    }

    private static List<AvailablePlanDto> buildAvailablePlans(PlanType currentPlan) {
        return java.util.Arrays.stream(PlanType.values())
                .filter(p -> p.ordinal() > currentPlan.ordinal())
                .map(p -> AvailablePlanDto.builder()
                        .name(p.name().toLowerCase())
                        .displayName(p.getDisplayName())
                        .price(p.getPrice())
                        .currency(p.getCurrency())
                        .billingCycle(p.getBillingCycle())
                        .benefits(buildBenefits(p))
                        .build())
                .toList();
    }
}
