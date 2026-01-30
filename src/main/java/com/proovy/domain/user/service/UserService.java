package com.proovy.domain.user.service;

import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.auth.repository.RefreshTokenRepository;
import com.proovy.domain.auth.service.AccessTokenBlacklistService;
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
import com.proovy.global.infra.s3.S3Service;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserPlanRepository userPlanRepository;
    private final AssetRepository assetRepository;
    private final NoteRepository noteRepository;
    private final S3Service s3Service;
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
        // TODO: Credit 도메인 구현 후 실제 데이터로 교체
        PlanType planType = userPlanRepository.findActivePlanTypeByUserId(userId)
                .orElse(PlanType.FREE);
        LocalDateTime tomorrow = LocalDate.now().plusDays(1).atStartOfDay();

        DailyCreditDto dailyCredit = DailyCreditDto.builder()
                .balance(100)
                .limit(100)
                .resetsAt(tomorrow.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();

        MonthlyCreditDto monthlyCredit = MonthlyCreditDto.builder()
                .balance(planType.getMonthlyCreditLimit())
                .limit(planType.getMonthlyCreditLimit())
                .expiresAt(null)
                .build();

        return CreditDto.builder()
                .dailyCredit(dailyCredit)
                .monthlyCredit(monthlyCredit)
                .totalAvailable(dailyCredit.balance() + monthlyCredit.balance())
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
        Optional<UserPlan> activePlan = userPlanRepository.findActiveByUserId(userId);
        if (activePlan.isPresent() && activePlan.get().getPlanType() != PlanType.FREE) {
            throw new BusinessException(ErrorCode.USER4004);
        }

        // 3. 사용자 관련 데이터 삭제
        deleteUserData(userId);

        // 4. 사용자 삭제
        userRepository.delete(user);

        // 5. 토큰 무효화
        accessTokenBlacklistService.blacklist(accessToken, userId);
        refreshTokenRepository.deleteByUserId(userId);

        log.info("사용자 탈퇴 완료: userId={}", userId);

        return DeleteUserResponse.of(
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
    }

    private void deleteUserData(Long userId) {
        // S3 키를 먼저 수집
        List<String> s3Keys = assetRepository.findAllByUserId(userId).stream()
                .map(asset -> asset.getS3Key())
                .collect(Collectors.toList());

        // DB 데이터 삭제
        assetRepository.deleteAllByUserId(userId);
        noteRepository.deleteAllByUserId(userId);
        userPlanRepository.deleteAllByUserId(userId);

        // 커밋 이후 S3 삭제 (best-effort)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                s3Keys.forEach(key -> {
                    try {
                        s3Service.deleteFile(key);
                    } catch (Exception e) {
                        log.warn("S3 파일 삭제 실패: s3Key={}", key, e);
                    }
                });
            }
        });
    }
}
