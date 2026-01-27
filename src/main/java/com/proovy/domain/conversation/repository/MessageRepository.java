package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /**
     * 특정 대화의 모든 메시지 조회
     */
    List<Message> findByConversationId(Long conversationId);

    /**
     * 여러 대화의 모든 메시지 조회 (생성 시각 순)
     */
    List<Message> findByConversationIdInOrderByCreatedAtAsc(List<Long> conversationIds);

    /**
     * 특정 대화의 모든 메시지 ID 조회
     */
    @Query("SELECT m.id FROM Message m WHERE m.conversation.id = :conversationId")
    List<Long> findIdsByConversationId(@Param("conversationId") Long conversationId);

    /**
     * 여러 대화의 모든 메시지 ID 조회
     */
    @Query("SELECT m.id FROM Message m WHERE m.conversation.id IN :conversationIds")
    List<Long> findIdsByConversationIdIn(@Param("conversationIds") List<Long> conversationIds);

    /**
     * 특정 대화들의 모든 메시지 삭제 (벌크 삭제)
     */
    @Modifying
    @Query("DELETE FROM Message m WHERE m.conversation.id IN :conversationIds")
    void deleteByConversationIdInBulk(@Param("conversationIds") List<Long> conversationIds);
}

