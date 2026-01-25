package com.proovy.domain.user.dto.response;

import com.proovy.domain.user.entity.User;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Builder
public record MyProfileResponse(
        Long userId,
        String email,
        String name,
        String nickname,
        String department,
        String profileImageUrl,
        String provider,
        String createdAt,
        SubscriptionDto subscription,
        CreditDto credit,
        StorageDto storage
) {
    @Builder
    public record SubscriptionDto(
            String plan,
            String startDate,
            String endDate
    ) {}

    @Builder
    public record CreditDto(
            DailyCreditDto dailyCredit,
            MonthlyCreditDto monthlyCredit,
            Integer totalAvailable
    ) {}

    @Builder
    public record DailyCreditDto(
            Integer balance,
            Integer limit,
            String resetsAt
    ) {}

    @Builder
    public record MonthlyCreditDto(
            Integer balance,
            Integer limit,
            String expiresAt
    ) {}

    @Builder
    public record StorageDto(
            Double used,
            Double limit,
            String unit
    ) {}

    public static MyProfileResponse from(
            User user,
            SubscriptionDto subscription,
            CreditDto credit,
            StorageDto storage
    ) {
        return MyProfileResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .nickname(user.getNickname())
                .department(user.getDepartment())
                .profileImageUrl(user.getProfileImageUrl())
                .provider(user.getProvider().name())
                .createdAt(user.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .subscription(subscription)
                .credit(credit)
                .storage(storage)
                .build();
    }
}
