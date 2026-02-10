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
    List<MessageTool> findByMessageId(Long messageId);

    /**
     * 여러 메시지의 모든 도구 연결 조회
     */
    List<MessageTool> findByMessageIdIn(List<Long> messageIds);

    /**
     * 여러 메시지의 모든 도구 연결 삭제 (벌크 삭제)
     */
    @Modifying
    @Query("DELETE FROM MessageTool mt WHERE mt.message.id IN :messageIds")
    void deleteByMessageIdInBulk(@Param("messageIds") List<Long> messageIds);

    /**
     * 특정 메시지들에 특정 도구 코드가 존재하는지 확인
     */
    @Query("SELECT COUNT(mt) > 0 FROM MessageTool mt WHERE mt.message.id IN :messageIds AND mt.toolCode = :toolCode")
    boolean existsByMessageIdInAndToolCode(@Param("messageIds") List<Long> messageIds, @Param("toolCode") String toolCode);

    /**
     * 특정 메시지들의 도구 코드 목록 조회
     */
    @Query("SELECT DISTINCT mt.toolCode FROM MessageTool mt WHERE mt.message.id IN :messageIds")
    List<String> findToolCodesByMessageIds(@Param("messageIds") List<Long> messageIds);
}

