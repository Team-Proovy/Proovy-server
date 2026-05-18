package com.proovy.domain.user.service;

import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.auth.repository.RefreshTokenRepository;
import com.proovy.domain.auth.service.AccessTokenBlacklistService;
import com.proovy.domain.conversation.repository.ChatMessageRepository;
import com.proovy.domain.conversation.repository.ChatSessionRepository;
import com.proovy.domain.conversation.repository.MessageAttachmentRepository;
import com.proovy.domain.credit.entity.CreditBalance;
import com.proovy.domain.credit.repository.CreditBalanceRepository;
import com.proovy.domain.credit.repository.CreditHistoryRepository;
import com.proovy.domain.credit.service.CreditBalanceService;
import com.proovy.domain.note.repository.NoteRepository;
import com.proovy.domain.user.dto.response.DeleteUserResponse;
import com.proovy.domain.user.dto.response.MyProfileResponse;
import com.proovy.domain.user.dto.response.MyProfileResponse.*;
import com.proovy.domain.user.entity.PlanType;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.entity.UserPlan;
import com.proovy.domain.user.repository.UserPlanRepository;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.infra.gcs.GcsService;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {
    private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Seoul");

    private final UserRepository userRepository;
    private final UserPlanRepository userPlanRepository;
    private final AssetRepository assetRepository;
    private final NoteRepository noteRepository;
    private final CreditBalanceRepository creditBalanceRepository;
    private final CreditHistoryRepository creditHistoryRepository;
    private final CreditBalanceService creditBalanceService;
    private final MessageAttachmentRepository messageAttachmentRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final GcsService s3Service;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessTokenBlacklistService accessTokenBlacklistService;

    /**
     * 내 프로필 조회
     */
    public MyProfileResponse getMyProfile(Long userId) {
        // 1. 사용자 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        // 2. 구독 정보 조회
        SubscriptionDto subscription = getSubscription(userId);

        // 3. 크레딧 정보 조회 (TODO: Credit 도메인 구현 후 연동)
        CreditDto credit = getCreditInfo(userId);

        // 4. 스토리지 정보 조회
        StorageDto storage = getStorageInfo(userId);

        return MyProfileResponse.from(user, subscription, credit, storage);
    }

    private SubscriptionDto getSubscription(Long userId) {
        return userPlanRepository.findActiveByUserId(userId)
                .map(plan -> SubscriptionDto.builder()
                        .plan(plan.getPlanType().getDisplayName())
                        .startDate(formatDate(plan.getStartedAt()))
                        .endDate(formatDate(plan.getExpiredAt()))
                        .build())
                .orElse(SubscriptionDto.builder()
                        .plan(PlanType.FREE.getDisplayName())
                        .startDate(null)
                        .endDate(null)
                        .build());
    }

    private CreditDto getCreditInfo(Long userId) {
        // 실제 크레딧 잔액 조회
        CreditBalance balance = creditBalanceService.getOrCreateBalance(userId);
        PlanType planType = userPlanRepository.findActivePlanTypeByUserId(userId)
                .orElse(PlanType.FREE);

        // 일일 크레딧: 매일 00:00에 100으로 리셋
        DailyCreditDto dailyCredit = DailyCreditDto.builder()
                .balance(balance.getDailyFreeCredit())
                .limit(balance.getDailyFreeLimit())
                .resetsAt(balance.getDailyExpiresAt() != null
                        ? balance.getDailyExpiresAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        : LocalDate.now(BILLING_ZONE).plusDays(1).atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();

        // 월간 크레딧: 유료 플랜 구독 시 부여되는 크레딧
        // FREE: 0, STANDARD: 2000, PRO: 5000
        int monthlyBalance = balance.getPaidCredit();  // paidCredit만 월간 크레딧
        int monthlyLimit = planType.getMonthlyCreditLimit();

        MonthlyCreditDto monthlyCredit = MonthlyCreditDto.builder()
                .balance(monthlyBalance)
                .limit(monthlyLimit)
                .expiresAt(balance.getPaidExpiresAt() != null
                        ? balance.getPaidExpiresAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        : null)
                .build();

        return CreditDto.builder()
                .dailyCredit(dailyCredit)
                .monthlyCredit(monthlyCredit)
                .totalAvailable(balance.getTotalAvailable())
                .build();
    }

    private StorageDto getStorageInfo(Long userId) {
        Long usedBytes = assetRepository.sumFileSizeByUserId(userId);
        double usedGb = usedBytes != null ? usedBytes / (1024.0 * 1024.0 * 1024.0) : 0.0;

        PlanType planType = userPlanRepository.findActivePlanTypeByUserId(userId)
                .orElse(PlanType.FREE);

        double limitGb = planType.getStorageLimitGb();

        return StorageDto.builder()
                .used(round2(usedGb))  // 소수 2자리
                .limit(limitGb)
                .unit("GB")
                .build();
    }

    private double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private String formatDate(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        return dateTime.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * 회원 탈퇴
     */
    @Transactional
    public DeleteUserResponse deleteUser(Long userId, String accessToken) {
        // 1. 사용자 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        // 2. 활성 구독 확인 (FREE가 아닌 플랜이 활성 상태면 탈퇴 불가)
        // 단, 취소 예약이 걸려있으면(canceledAt != null && nextPlanType == FREE) 탈퇴 허용
        Optional<UserPlan> activePlan = userPlanRepository.findActiveByUserId(userId);
        if (activePlan.isPresent() && activePlan.get().getPlanType() != PlanType.FREE) {
            UserPlan plan = activePlan.get();
            // 취소 예약 상태가 아니면 탈퇴 불가
            boolean isCancelScheduled = plan.getCanceledAt() != null &&
                                         plan.getNextPlanType() == PlanType.FREE;
            if (!isCancelScheduled) {
                throw new BusinessException(ErrorCode.USER4006);
            }
        }

        // 3. 사용자 관련 데이터 삭제
        deleteUserData(userId);

        // 4. 사용자 삭제
        userRepository.delete(user);

        // 5. 토큰 무효화
        refreshTokenRepository.deleteByUserId(userId);
        // Access Token 블랙리스트는 Redis 기반이므로 커밋 이후 실행
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    if (accessToken != null) {
                        accessTokenBlacklistService.blacklist(accessToken, userId);
                    }
                } catch (Exception e) {
                    log.warn("Access Token 블랙리스트 실패: userId={}", userId, e);
                }
            }
        });

        log.info("사용자 탈퇴 완료: userId={}", userId);

        return DeleteUserResponse.of(
                nowInBillingZone().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
    }

    private void deleteUserData(Long userId) {
        // GCS 키를 먼저 수집 (원본 + 썸네일)
        List<String> objectKeys = new java.util.ArrayList<>();
        assetRepository.findAllByUserId(userId).forEach(asset -> {
            if (asset.getObjectKey() != null) {
                objectKeys.add(asset.getObjectKey());
            }
            if (asset.getThumbnailObjectKey() != null) {
                objectKeys.add(asset.getThumbnailObjectKey());
            }
        });

        // DB 데이터 삭제 (FK 자식 → 부모 순서)
        messageAttachmentRepository.deleteAllByUserId(userId);
        chatMessageRepository.deleteAllByUserId(userId);
        chatSessionRepository.deleteAllByUserId(userId);
        creditHistoryRepository.deleteAllByUserId(userId);
        creditBalanceRepository.deleteAllByUserId(userId);
        assetRepository.deleteAllByUserId(userId);
        noteRepository.deleteAllByUserId(userId);
        userPlanRepository.deleteAllByUserId(userId);

        // 커밋 이후 S3 삭제 (best-effort)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                objectKeys.forEach(key -> {
                    try {
                        s3Service.deleteFile(key);
                    } catch (Exception e) {
                        log.warn("GCS 파일 삭제 실패: objectKey={}", key, e);
                    }
                });
            }
        });
    }

    private LocalDateTime nowInBillingZone() {
        return ZonedDateTime.now(BILLING_ZONE).toLocalDateTime();
    }
}
