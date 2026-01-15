package com.proovy.domain.asset.repository;

import com.proovy.domain.asset.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
