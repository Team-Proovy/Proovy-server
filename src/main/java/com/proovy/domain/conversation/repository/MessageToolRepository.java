package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.MessageTool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageToolRepository extends JpaRepository<MessageTool, Long> {

    /**
     * 특정 메시지의 모든 도구 연결 조회
     */
    List<MessageTool> findByChatMessageId(Long chatMessageId);

    /**
     * 여러 메시지의 모든 도구 연결 조회
     */
    List<MessageTool> findByChatMessageIdIn(List<Long> chatMessageIds);

    /**
     * 여러 메시지의 모든 도구 연결 삭제 (벌크 삭제)
     */
    @Modifying
    @Query("DELETE FROM MessageTool mt WHERE mt.chatMessage.id IN :chatMessageIds")
    void deleteByChatMessageIdInBulk(@Param("chatMessageIds") List<Long> chatMessageIds);

    /**
     * 특정 메시지들에 특정 도구 코드가 존재하는지 확인
     */
    @Query("SELECT COUNT(mt) > 0 FROM MessageTool mt WHERE mt.chatMessage.id IN :chatMessageIds AND mt.toolCode = :toolCode")
    boolean existsByChatMessageIdInAndToolCode(@Param("chatMessageIds") List<Long> chatMessageIds, @Param("toolCode") String toolCode);

    /**
     * 특정 메시지들의 도구 코드 목록 조회
     */
    @Query("SELECT DISTINCT mt.toolCode FROM MessageTool mt WHERE mt.chatMessage.id IN :chatMessageIds")
    List<String> findToolCodesByChatMessageIds(@Param("chatMessageIds") List<Long> chatMessageIds);
}

