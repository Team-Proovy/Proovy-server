package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.MessageAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageAssetRepository extends JpaRepository<MessageAsset, Long> {

    /**
     * 특정 메시지의 모든 자산 연결 조회
     */
    List<MessageAsset> findByMessageId(Long messageId);

    /**
     * 여러 메시지의 모든 자산 연결 조회
     */
    List<MessageAsset> findByMessageIdIn(List<Long> messageIds);

    /**
     * 특정 자산을 참조하는 모든 MessageAsset 조회
     */
    List<MessageAsset> findByAssetId(Long assetId);

    /**
     * 특정 자산들을 참조하는 모든 MessageAsset 조회
     */
    List<MessageAsset> findByAssetIdIn(List<Long> assetIds);

    /**
     * 특정 자산들을 참조하는 모든 MessageAsset 삭제 (벌크 삭제)
     */
    @Modifying
    @Query("DELETE FROM MessageAsset ma WHERE ma.asset.id IN :assetIds")
    void deleteByAssetIdInBulk(@Param("assetIds") List<Long> assetIds);

    /**
     * 여러 메시지의 모든 자산 연결 삭제 (벌크 삭제)
     */
    @Modifying
    @Query("DELETE FROM MessageAsset ma WHERE ma.message.id IN :messageIds")
    void deleteByMessageIdInBulk(@Param("messageIds") List<Long> messageIds);
}

