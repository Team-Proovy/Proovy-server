package com.proovy.domain.conversation.service;

import com.proovy.domain.conversation.dto.request.CanvasImageUploadRequest;
import com.proovy.domain.conversation.dto.response.CanvasImageUploadResponse;
import com.proovy.domain.conversation.dto.response.ConversationDetailResponse;
import com.proovy.domain.conversation.dto.response.ConversationSearchResponse;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

/**
 * 대화 검색 및 조회 서비스 (ChatMessage 기반)
 */
public interface ConversationQueryService {

    /**
     * 캔버스 이미지 업로드 URL 발급
     */
    CanvasImageUploadResponse uploadCanvasImage(Long userId, CanvasImageUploadRequest request);

    /**
     * 대화 검색
     */
    ConversationSearchResponse searchConversations(
            Long userId,
            String query,
            Long noteId,
            String toolCode,
            LocalDate startDate,
            LocalDate endDate,
            Pageable pageable
    );

    /**
     * 대화 상세 조회
     */
    ConversationDetailResponse getConversationDetail(Long userId, Long conversationId);
}
