package com.proovy.domain.asset.repository;

import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.entity.AssetStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    /**
     * 여러 자산 ID로 조회 (일괄 삭제용)
     */
    List<Asset> findAllByIdIn(List<Long> ids);

    /**
     * 특정 사용자의 자산인지 확인
     */
    @Query("SELECT COUNT(a) FROM Asset a WHERE a.id IN :ids AND a.userId = :userId")
    long countByIdInAndUserId(@Param("ids") List<Long> ids, @Param("userId") Long userId);

    /**
     * 특정 사용자의 자산 목록 조회
     */
    List<Asset> findAllByUserId(Long userId);

    /**
     * 특정 노트의 자산 목록 조회
     */
    List<Asset> findAllByNoteId(Long noteId);

    /**
     * 특정 노트의 특정 상태 자산 파일 크기 합계 조회
     */
    @Query("SELECT COALESCE(SUM(a.fileSize), 0) FROM Asset a WHERE a.noteId = :noteId AND a.status = :status")
    Long sumFileSizeByNoteIdAndStatus(@Param("noteId") Long noteId, @Param("status") AssetStatus status);

    /**
     * 특정 사용자의 UPLOADED 상태 자산 파일 크기 합계 조회
     * (PENDING, FAILED 상태는 실제 S3에 저장되지 않으므로 제외)
     */
    @Query("SELECT COALESCE(SUM(a.fileSize), 0) FROM Asset a WHERE a.userId = :userId AND a.status = 'UPLOADED'")
    Long sumFileSizeByUserId(@Param("userId") Long userId);

    /**
     * OCR 상태가 특정 값이고 updatedAt이 특정 시각 이전인 자산 목록 조회 (타임아웃 처리용)
     */
    List<Asset> findByOcrStatusAndUpdatedAtBefore(Asset.OcrStatus ocrStatus, LocalDateTime threshold);

    /**
     * 특정 사용자의 모든 자산 삭제 (회원 탈퇴용)
     */
    void deleteAllByUserId(Long userId);
}
