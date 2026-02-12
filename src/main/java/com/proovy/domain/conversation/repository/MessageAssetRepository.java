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
    List<MessageAsset> findByChatMessageId(Long chatMessageId);

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
    @Query("DELETE FROM MessageAsset ma WHERE ma.chatMessage.id IN :chatMessageIds")
    void deleteByChatMessageIdInBulk(@Param("chatMessageIds") List<Long> chatMessageIds);

    List<MessageAsset> findByChatMessageIdIn(List<Long> chatMessageIds);

    /**
     * 특정 메시지들에 연결된 Asset 목록 조회
     */
    @Query("SELECT ma.asset FROM MessageAsset ma WHERE ma.chatMessage.id IN :chatMessageIds")
    List<com.proovy.domain.asset.entity.Asset> findAssetsByChatMessageIds(@Param("chatMessageIds") List<Long> chatMessageIds);
}

