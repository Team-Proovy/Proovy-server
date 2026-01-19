package com.proovy.domain.storage.service;

import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.note.entity.Note;
import com.proovy.domain.note.repository.NoteRepository;
import com.proovy.domain.storage.dto.response.StorageResponse;
import com.proovy.domain.user.entity.PlanType;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.entity.UserPlan;
import com.proovy.domain.user.repository.UserPlanRepository;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.infra.s3.S3Service;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StorageServiceTest {

    @InjectMocks
    private StorageService storageService;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NoteRepository noteRepository;

    @Mock
    private UserPlanRepository userPlanRepository;

    @Mock
    private S3Service s3Service;

    private User testUser;
    private Note testNote;
    private Asset testAsset;
    private UserPlan freePlan;
    private UserPlan premiumPlan;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .nickname("테스트유저")
                .build();

        testNote = Note.builder()
                .user(testUser)
                .title("테스트 노트")
                .build();

        testAsset = Asset.builder()
                .userId(1L)
                .noteId(1L)
                .fileName("test.pdf")
                .fileSize(1024L * 1024L * 100) // 100MB
                .mimeType("application/pdf")
                .s3Key("users/1/assets/test.pdf")
                .source(Asset.AssetSource.upload)
                .build();

        freePlan = UserPlan.builder()
                .user(testUser)
                .planType(PlanType.FREE)
                .isActive(true)
                .build();

        premiumPlan = UserPlan.builder()
                .user(testUser)
                .planType(PlanType.PREMIUM)
                .isActive(true)
                .build();
    }

    @Nested
    @DisplayName("getStorageUsage 메서드")
    class GetStorageUsage {

        @Test
        @DisplayName("성공 - 스토리지 사용량을 정상적으로 조회한다")
        void success() {
            // given
            Long userId = 1L;
            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(userPlanRepository.findActiveByUserId(userId)).willReturn(Optional.of(freePlan));
            given(noteRepository.findByUserIdOrderByCreatedAtDesc(userId)).willReturn(List.of(testNote));
            given(assetRepository.findAllByUserId(userId)).willReturn(List.of(testAsset));
            given(s3Service.getThumbnailUrl(any())).willReturn(null);

            // when
            StorageResponse response = storageService.getStorageUsage(userId, null);

            // then
            assertThat(response).isNotNull();
            assertThat(response.totalLimit()).isEqualTo(3000); // FREE 플랜 3GB
            assertThat(response.plan().planType()).isEqualTo("free");
            assertThat(response.plan().isActive()).isTrue();
        }

        @Test
        @DisplayName("성공 - 검색어로 노트를 필터링한다")
        void successWithKeyword() {
            // given
            Long userId = 1L;
            String keyword = "테스트";
            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(userPlanRepository.findActiveByUserId(userId)).willReturn(Optional.of(freePlan));
            given(noteRepository.searchByTitleKeyword(userId, keyword)).willReturn(List.of(testNote));
            given(assetRepository.findAllByUserId(userId)).willReturn(List.of(testAsset));
            given(s3Service.getThumbnailUrl(any())).willReturn(null);

            // when
            StorageResponse response = storageService.getStorageUsage(userId, keyword);

            // then
            assertThat(response).isNotNull();
            assertThat(response.notes()).hasSize(1);
        }

        @Test
        @DisplayName("실패 - 사용자가 존재하지 않으면 예외를 던진다")
        void failUserNotFound() {
            // given
            Long userId = 999L;
            given(userRepository.findById(userId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> storageService.getStorageUsage(userId, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.USER4041);
        }

        @Test
        @DisplayName("실패 - 검색어가 2자 미만이면 예외를 던진다")
        void failKeywordTooShort() {
            // given
            Long userId = 1L;
            String keyword = "테"; // 1자
            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));

            // when & then
            assertThatThrownBy(() -> storageService.getStorageUsage(userId, keyword))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.STORAGE4003);
        }

        @Test
        @DisplayName("성공 - 프리미엄 플랜은 100GB 제한이다")
        void successPremiumPlan() {
            // given
            Long userId = 1L;
            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(userPlanRepository.findActiveByUserId(userId)).willReturn(Optional.of(premiumPlan));
            given(noteRepository.findByUserIdOrderByCreatedAtDesc(userId)).willReturn(List.of());
            given(assetRepository.findAllByUserId(userId)).willReturn(List.of());

            // when
            StorageResponse response = storageService.getStorageUsage(userId, null);

            // then
            assertThat(response.totalLimit()).isEqualTo(100000); // PREMIUM 플랜 100GB
            assertThat(response.plan().planType()).isEqualTo("premium");
        }

        @Test
        @DisplayName("성공 - 플랜이 없으면 FREE 플랜이 기본값이다")
        void successDefaultFreePlan() {
            // given
            Long userId = 1L;
            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(userPlanRepository.findActiveByUserId(userId)).willReturn(Optional.empty());
            given(noteRepository.findByUserIdOrderByCreatedAtDesc(userId)).willReturn(List.of());
            given(assetRepository.findAllByUserId(userId)).willReturn(List.of());

            // when
            StorageResponse response = storageService.getStorageUsage(userId, null);

            // then
            assertThat(response.totalLimit()).isEqualTo(3000); // FREE 플랜 3GB
            assertThat(response.plan().planType()).isEqualTo("free");
        }

        @Test
        @DisplayName("성공 - 용량 계산이 올바르게 수행된다")
        void successStorageCalculation() {
            // given
            Long userId = 1L;
            Asset asset1 = Asset.builder()
                    .userId(1L)
                    .noteId(1L)
                    .fileName("file1.pdf")
                    .fileSize(1024L * 1024L * 200) // 200MB
                    .mimeType("application/pdf")
                    .s3Key("key1")
                    .source(Asset.AssetSource.upload)
                    .build();

            Asset asset2 = Asset.builder()
                    .userId(1L)
                    .noteId(1L)
                    .fileName("file2.png")
                    .fileSize(1024L * 1024L * 50) // 50MB
                    .mimeType("image/png")
                    .s3Key("key2")
                    .source(Asset.AssetSource.upload)
                    .build();

            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(userPlanRepository.findActiveByUserId(userId)).willReturn(Optional.of(freePlan));
            given(noteRepository.findByUserIdOrderByCreatedAtDesc(userId)).willReturn(List.of(testNote));
            given(assetRepository.findAllByUserId(userId)).willReturn(List.of(asset1, asset2));
            given(s3Service.getThumbnailUrl(any())).willReturn(null);

            // when
            StorageResponse response = storageService.getStorageUsage(userId, null);

            // then
            assertThat(response.totalUsed()).isEqualTo(250); // 200 + 50 = 250MB
        }

        @Test
        @DisplayName("성공 - 노트가 없으면 빈 배열을 반환한다")
        void successEmptyNotes() {
            // given
            Long userId = 1L;
            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(userPlanRepository.findActiveByUserId(userId)).willReturn(Optional.of(freePlan));
            given(noteRepository.findByUserIdOrderByCreatedAtDesc(userId)).willReturn(List.of());
            given(assetRepository.findAllByUserId(userId)).willReturn(List.of());

            // when
            StorageResponse response = storageService.getStorageUsage(userId, null);

            // then
            assertThat(response.notes()).isEmpty();
            assertThat(response.totalUsed()).isEqualTo(0);
        }
    }
}
