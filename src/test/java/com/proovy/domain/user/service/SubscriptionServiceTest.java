package com.proovy.domain.user.service;

import com.proovy.domain.user.dto.response.SubscriptionResponse;
import com.proovy.domain.user.entity.PlanType;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.entity.UserPlan;
import com.proovy.domain.user.repository.UserPlanRepository;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionServiceTest {

    @InjectMocks
    private SubscriptionService subscriptionService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserPlanRepository userPlanRepository;

    private User testUser;
    private UserPlan freePlan;
    private UserPlan standardPlan;
    private UserPlan proPlan;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .nickname("테스트유저")
                .build();
        ReflectionTestUtils.setField(testUser, "id", 1L);

        freePlan = UserPlan.builder()
                .user(testUser)
                .planType(PlanType.FREE)
                .isActive(true)
                .build();

        standardPlan = UserPlan.builder()
                .user(testUser)
                .planType(PlanType.STANDARD)
                .startedAt(LocalDateTime.now().minusDays(10))
                .expiredAt(LocalDateTime.now().plusDays(20))
                .isActive(true)
                .build();

        proPlan = UserPlan.builder()
                .user(testUser)
                .planType(PlanType.PRO)
                .startedAt(LocalDateTime.now().minusDays(10))
                .expiredAt(LocalDateTime.now().plusDays(20))
                .isActive(true)
                .build();
    }

    @Nested
    @DisplayName("getSubscription 메서드")
    class GetSubscription {

        @Test
        @DisplayName("성공 - Free 플랜 사용자는 Standard, Pro 플랜을 업그레이드 옵션으로 받는다")
        void successFreePlan() {
            // given
            Long userId = 1L;
            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(userPlanRepository.findActiveByUserId(userId)).willReturn(Optional.of(freePlan));

            // when
            SubscriptionResponse response = subscriptionService.getSubscription(userId);

            // then
            assertThat(response.currentPlan().name()).isEqualTo("free");
            assertThat(response.currentPlan().price()).isEqualTo(0);
            assertThat(response.period()).isNull();
            assertThat(response.billing()).isNull();
            assertThat(response.availablePlans()).hasSize(2);
            assertThat(response.availablePlans().get(0).name()).isEqualTo("standard");
            assertThat(response.availablePlans().get(1).name()).isEqualTo("pro");
        }

        @Test
        @DisplayName("성공 - Standard 플랜 사용자는 Pro 플랜만 업그레이드 옵션으로 받는다")
        void successStandardPlan() {
            // given
            Long userId = 1L;
            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(userPlanRepository.findActiveByUserId(userId)).willReturn(Optional.of(standardPlan));

            // when
            SubscriptionResponse response = subscriptionService.getSubscription(userId);

            // then
            assertThat(response.currentPlan().name()).isEqualTo("standard");
            assertThat(response.currentPlan().price()).isEqualTo(6900);
            assertThat(response.period()).isNotNull();
            assertThat(response.period().daysRemaining()).isEqualTo(20);
            assertThat(response.billing()).isNotNull();
            assertThat(response.availablePlans()).hasSize(1);
            assertThat(response.availablePlans().get(0).name()).isEqualTo("pro");
        }

        @Test
        @DisplayName("성공 - Pro 플랜 사용자는 업그레이드 옵션이 빈 배열이다")
        void successProPlan() {
            // given
            Long userId = 1L;
            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(userPlanRepository.findActiveByUserId(userId)).willReturn(Optional.of(proPlan));

            // when
            SubscriptionResponse response = subscriptionService.getSubscription(userId);

            // then
            assertThat(response.currentPlan().name()).isEqualTo("pro");
            assertThat(response.currentPlan().price()).isEqualTo(14900);
            assertThat(response.period()).isNotNull();
            assertThat(response.billing()).isNotNull();
            assertThat(response.billing().autoRenew()).isTrue();
            assertThat(response.availablePlans()).isEmpty();
        }

        @Test
        @DisplayName("성공 - 플랜이 없으면 기본 Free 플랜을 반환한다")
        void successDefaultFreePlan() {
            // given
            Long userId = 1L;
            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(userPlanRepository.findActiveByUserId(userId)).willReturn(Optional.empty());

            // when
            SubscriptionResponse response = subscriptionService.getSubscription(userId);

            // then
            assertThat(response.currentPlan().name()).isEqualTo("free");
            assertThat(response.period()).isNull();
            assertThat(response.billing()).isNull();
        }

        @Test
        @DisplayName("성공 - 혜택 정보가 올바르게 반환된다")
        void successBenefitsInfo() {
            // given
            Long userId = 1L;
            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(userPlanRepository.findActiveByUserId(userId)).willReturn(Optional.of(proPlan));

            // when
            SubscriptionResponse response = subscriptionService.getSubscription(userId);

            // then
            assertThat(response.benefits().dailyCredit()).isEqualTo(100);
            assertThat(response.benefits().monthlyCredit()).isEqualTo(5000);
            assertThat(response.benefits().storageLimit()).isEqualTo("10GB");
            assertThat(response.benefits().maxFileSize()).isEqualTo("100MB");
            assertThat(response.benefits().maxNotes()).isEqualTo(20);
        }

        @Test
        @DisplayName("실패 - 사용자가 존재하지 않으면 예외를 던진다")
        void failUserNotFound() {
            // given
            Long userId = 999L;
            given(userRepository.findById(userId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> subscriptionService.getSubscription(userId))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.USER4041);
        }
    }
}
