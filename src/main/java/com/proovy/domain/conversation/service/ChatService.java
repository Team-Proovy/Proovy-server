package com.proovy.domain.conversation.service;

import com.proovy.domain.conversation.dto.request.ConversationRequest;
import com.proovy.domain.conversation.dto.response.ConversationResponse;
import com.proovy.domain.conversation.dto.response.ProovyAiStreamEvent;
import reactor.core.publisher.Flux;

/**
 * 채팅 세션 서비스 인터페이스
 */
public interface ChatService {

    /**
     * 스트리밍 대화 요청 (SSE)
     * @param userId 사용자 ID
     * @param request 대화 요청
     * @param accessToken 사용자 인증 토큰 (AI 서버에서 Spring API 호출 시 사용)
     * @return SSE 스트림
     */
    Flux<ProovyAiStreamEvent> streamConversation(Long userId, ConversationRequest request, String accessToken);

    /**
     * 단건 대화 요청 (향후 구현)
     * @param userId 사용자 ID
     * @param request 대화 요청
     * @param accessToken 사용자 인증 토큰 (AI 서버에서 Spring API 호출 시 사용)
     * @return 대화 응답
     */
    ConversationResponse invokeConversation(Long userId, ConversationRequest request, String accessToken);
}
