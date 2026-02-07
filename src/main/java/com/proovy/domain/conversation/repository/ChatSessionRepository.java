package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.ChatSession;
import com.proovy.domain.conversation.entity.ChatSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    /**
     * 특정 사용자의 활성 세션 목록 조회
     */
    List<ChatSession> findByUserIdAndStatus(Long userId, ChatSessionStatus status);

    /**
     * 특정 사용자의 가장 최근 활성 세션 조회
     */
    Optional<ChatSession> findFirstByUserIdAndStatusOrderByCreatedAtDesc(Long userId, ChatSessionStatus status);

    /**
     * 외부 스레드 ID로 세션 조회
     */
    Optional<ChatSession> findByExternalThreadId(String externalThreadId);

    /**
     * 특정 사용자의 모든 세션 개수 조회
     */
    long countByUserId(Long userId);

    @Modifying
    @Query("DELETE FROM ChatSession cs WHERE cs.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
