package com.proovy.domain.storage.service;

import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.note.entity.Note;
import com.proovy.domain.note.repository.NoteRepository;
import com.proovy.domain.storage.dto.request.BulkDeleteRequest;
import com.proovy.domain.storage.dto.response.*;
import com.proovy.domain.user.entity.PlanType;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.repository.UserPlanRepository;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.infra.s3.S3Service;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StorageService {

    private final AssetRepository assetRepository;
    private final UserRepository userRepository;
    private final NoteRepository noteRepository;
    private final UserPlanRepository userPlanRepository;
    private final S3Service s3Service;

    /**
     * 자산 일괄 삭제
     * TODO: JWT 인증 구현 후 @AuthenticationPrincipal로 userId 추출
     *
     * @param userId 사용자 ID (현재는 파라미터로 전달, 추후 JWT에서 추출)
     * @param request 삭제할 자산 ID 목록
     * @return 삭제된 자산 정보
     */
    @Transactional
    public BulkDeleteResponse bulkDeleteAssets(Long userId, BulkDeleteRequest request) {
        List<Long> assetIds = request.assetIds();

        // 자산 존재 여부 확인
        List<Asset> assets = assetRepository.findAllByIdIn(assetIds);
        if (assets.size() != assetIds.size()) {
            throw new BusinessException(ErrorCode.STORAGE4001);
        }

        // 권한 검증 (본인 소유 자산만 삭제 가능)
        long ownedCount = assetRepository.countByIdInAndUserId(assetIds, userId);
        if (ownedCount != assetIds.size()) {
            throw new BusinessException(ErrorCode.STORAGE4031);
        }

        // S3 키 수집 (원본 + 썸네일)
        List<String> s3KeysToDelete = new ArrayList<>();
        long totalFileSize = 0L;

        for (Asset asset : assets) {
            // 원본 파일
            s3KeysToDelete.add(asset.getS3Key());
            totalFileSize += asset.getFileSize();

            // 썸네일 파일
            if (asset.getThumbnailS3Key() != null) {
                s3KeysToDelete.add(asset.getThumbnailS3Key());
            }
        }

        // DB에서 자산 삭제
        assetRepository.deleteAllInBatch(assets);

        // S3에서 파일 삭제
        s3Service.deleteFiles(s3KeysToDelete);

        // 스토리지 용량 반환 로깅
        log.info("[Storage] 사용자 {} - {} 개 파일 삭제, 용량 반환: {} bytes",
                userId, assets.size(), totalFileSize);

        return BulkDeleteResponse.of(assetIds);
    }

    /**
     * 전체 저장소 사용량 및 노트별 현황 조회
     * TODO: JWT 인증 구현 후 @AuthenticationPrincipal로 userId 추출
     *
     * @param userId 사용자 ID
     * @param keyword 검색어 (노트 제목, 파일명 검색) - nullable
     * @return 스토리지 사용 현황
     */
    public StorageResponse getStorageUsage(Long userId, String keyword) {
        // 사용자 존재 확인
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        // 검색어 길이 검증
        if (keyword != null && !keyword.isBlank() && keyword.length() < 2) {
            throw new BusinessException(ErrorCode.STORAGE4003);
        }

        // 사용자 플랜 조회 (없으면 FREE)
        PlanType planType = userPlanRepository.findActivePlanTypeByUserId(userId)
                .orElse(PlanType.FREE);
        int totalLimitMb = planType.getStorageLimitMb();

        // 노트 목록 조회 (검색어 있으면 필터링)
        List<Note> notes;
        if (keyword != null && !keyword.isBlank()) {
            notes = noteRepository.searchByTitleKeyword(userId, keyword);
        } else {
            notes = noteRepository.findByUserIdOrderByCreatedAtDesc(userId);
        }

        // 사용자의 전체 자산 조회
        List<Asset> allAssets = assetRepository.findAllByUserId(userId);

        // 노트별 자산 그룹핑
        Map<Long, List<Asset>> assetsByNoteId = allAssets.stream()
                .collect(Collectors.groupingBy(Asset::getNoteId));

        // 검색어가 있으면 파일명으로도 필터링된 노트 추가
        if (keyword != null && !keyword.isBlank()) {
            String lowerKeyword = keyword.toLowerCase();
            for (Asset asset : allAssets) {
                if (asset.getFileName() != null &&
                    asset.getFileName().toLowerCase().contains(lowerKeyword)) {
                    // 해당 노트가 이미 포함되어 있지 않으면 추가
                    Long noteId = asset.getNoteId();
                    boolean alreadyIncluded = notes.stream()
                            .anyMatch(n -> n.getId().equals(noteId));
                    if (!alreadyIncluded) {
                        noteRepository.findById(noteId).ifPresent(notes::add);
                    }
                }
            }
        }

        // 전체 사용 용량 계산 (bytes -> MB)
        long totalUsedBytes = allAssets.stream()
                .mapToLong(a -> a.getFileSize() != null ? a.getFileSize() : 0L)
                .sum();
        int totalUsedMb = (int) (totalUsedBytes / (1024 * 1024));

        // 노트별 DTO 생성
        List<NoteStorageDto> noteDtos = notes.stream()
                .map(note -> createNoteStorageDto(note, assetsByNoteId.getOrDefault(note.getId(), List.of())))
                .toList();

        return StorageResponse.of(
                totalUsedMb,
                totalLimitMb,
                planType.getDisplayName(),
                true, // 현재 활성 플랜
                noteDtos
        );
    }

    private NoteStorageDto createNoteStorageDto(Note note, List<Asset> assets) {
        // 노트별 사용 용량 계산
        long noteUsedBytes = assets.stream()
                .mapToLong(a -> a.getFileSize() != null ? a.getFileSize() : 0L)
                .sum();
        int noteUsedMb = (int) (noteUsedBytes / (1024 * 1024));

        // 자산 DTO 생성
        List<AssetSummaryDto> assetDtos = assets.stream()
                .map(asset -> AssetSummaryDto.from(
                        asset,
                        s3Service.getThumbnailUrl(asset.getThumbnailS3Key())
                ))
                .toList();

        return NoteStorageDto.of(
                note.getId(),
                note.getTitle(),
                noteUsedMb,
                assetDtos
        );
    }
}
