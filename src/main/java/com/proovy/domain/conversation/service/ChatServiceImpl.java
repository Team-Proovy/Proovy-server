package com.proovy.domain.conversation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.conversation.dto.request.ConversationRequest;
import com.proovy.domain.conversation.dto.request.ProovyAiRequest;
import com.proovy.domain.conversation.dto.response.ConversationResponse;
import com.proovy.domain.conversation.dto.response.ProovyAiStreamEvent;
import com.proovy.domain.conversation.entity.ChatMessage;
import com.proovy.domain.conversation.entity.ChatSession;
import com.proovy.domain.conversation.entity.ChatSessionStatus;
import com.proovy.domain.conversation.entity.MessageRole;
import com.proovy.domain.conversation.repository.ChatMessageRepository;
import com.proovy.domain.conversation.repository.ChatSessionRepository;
import com.proovy.domain.conversation.repository.MessageAttachmentRepository;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.infra.s3.S3Service;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MessageAttachmentRepository messageAttachmentRepository;
    private final UserRepository userRepository;
    private final AssetRepository assetRepository;
    private final S3Service s3Service;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${proovy.ai.host}")
    private String proovyAiHost;

    // 지원되는 기능 목록 (향후 확장 가능)
    private static final Set<String> SUPPORTED_FEATURES = Set.of(
            "Solve", "Check", "Explain", "Variant", "Practice"
    );

    @Override
    @Transactional
    public Flux<ProovyAiStreamEvent> streamConversation(Long userId, ConversationRequest request) {
        // 1. 사용자 검증
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        // 2. 기능 검증
        if (request.getChosenFeatures() != null) {
            validateFeatures(request.getChosenFeatures());
        }

        // 3. ChatSession 조회 또는 생성 (사용자의 가장 최근 활성 세션 사용)
        ChatSession chatSession = chatSessionRepository
                .findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, ChatSessionStatus.ACTIVE)
                .orElseGet(() -> {
                    ChatSession newSession = ChatSession.builder()
                            .user(user)
                            .status(ChatSessionStatus.ACTIVE)
                            .build();
                    return chatSessionRepository.save(newSession);
                });

        // 4. 사용자 메시지 저장
        JsonNode userContentJson = buildUserContentJson(request);
        ChatMessage userMessage = ChatMessage.builder()
                .chatSession(chatSession)
                .role(MessageRole.USER)
                .content(userContentJson)
                .messageType("text")
                .build();
        chatMessageRepository.save(userMessage);

        // 5. AI 메시지 placeholder 생성
        ObjectNode emptyContent = objectMapper.createObjectNode();
        emptyContent.put("text", "");
        
        ChatMessage aiMessage = ChatMessage.builder()
                .chatSession(chatSession)
                .role(MessageRole.ASSISTANT)
                .content(emptyContent)
                .messageType("text")
                .build();
        ChatMessage savedAiMessage = chatMessageRepository.save(aiMessage);

        // 6. 자산 URL 변환
        List<String> filesUrl = convertAssetIdsToUrls(request.getMentionedAssetIds(), userId);

        // 7. Proovy-ai 요청 생성 (Proovy-ai StreamInput 스키마에 맞게 구성)
        ProovyAiRequest aiRequest = ProovyAiRequest.builder()
            .message(request.getText())
            .threadId(chatSession.getExternalThreadId())
            .userId(String.valueOf(userId))
            .filesUrl(filesUrl)
            .chosenFeatures(request.getChosenFeatures())
            .streamTokens(true)
            .agentConfig(buildMetadata(request))
            .build();

        // 8. SSE 스트리밍 호출
        final StringBuilder contentBuilder = new StringBuilder();
        
        return callProovyAiStream(aiRequest)
                .doOnNext(event -> {
                    Map<String, Object> data = event.getData();

                    // thread_id 업데이트 (payload 내부에 포함되는 경우)
                    if (data != null && data.containsKey("thread_id")) {
                        String threadId = (String) data.get("thread_id");
                        if (chatSession.getExternalThreadId() == null && threadId != null) {
                            chatSession.updateExternalThreadId(threadId);
                            chatSessionRepository.save(chatSession);
                        }
                    }

                    // 토큰 스트림(type = token) 기준으로 내용 누적
                    if ("token".equals(event.getEvent()) && data != null) {
                        Object content = data.get("content");
                        if (content != null) {
                            contentBuilder.append(content.toString());
                        }
                    }
                })
                .doOnComplete(() -> {
                    // 스트리밍 완료 시 최종 내용 저장
                    ObjectNode finalContent = objectMapper.createObjectNode();
                    finalContent.put("text", contentBuilder.toString());
                    savedAiMessage.updateContent(finalContent);
                    chatMessageRepository.save(savedAiMessage);
                    
                    log.info("Streaming completed for session: {}, message: {}", 
                            chatSession.getId(), savedAiMessage.getId());
                })
                .doOnError(error -> {
                    // 에러 발생 시 메시지 삭제
                    log.error("Streaming error for session: {}", chatSession.getId(), error);
                    chatMessageRepository.delete(savedAiMessage);
                })
                .onErrorResume(error -> {
                    log.error("Proovy-ai streaming failed", error);
                    return Flux.error(new BusinessException(ErrorCode.CONV5002, 
                            "스트리밍 중 오류 발생: " + error.getMessage()));
                });
    }

    @Override
    @Transactional
    public ConversationResponse invokeConversation(Long userId, ConversationRequest request) {
        // TODO: 향후 isStream=false 시 구현
        throw new UnsupportedOperationException("invoke 모드는 아직 구현되지 않았습니다.");
    }

    /**
     * Proovy-ai /stream 호출
     */
    private Flux<ProovyAiStreamEvent> callProovyAiStream(ProovyAiRequest request) {
        String streamUrl = proovyAiHost + "/stream";
        
        log.info("Calling Proovy-ai stream: {}", streamUrl);
        log.debug("Request payload: {}", request);

        return webClient.post()
                .uri(streamUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofMinutes(5))
                .flatMap(this::parseSseEvent)
                .doOnNext(event -> log.debug("Received event: {}", event.getEvent()))
                .takeUntil(event -> "[DONE]".equals(event.getEvent()));
    }

    /**
     * SSE 이벤트 파싱
     */
    private Mono<ProovyAiStreamEvent> parseSseEvent(String rawEvent) {
        return Mono.fromCallable(() -> {
            try {
                if (rawEvent == null) {
                    return ProovyAiStreamEvent.builder()
                            .event("message")
                            .data(Collections.emptyMap())
                            .build();
                }

                String trimmed = rawEvent.trim();

                // WebClient 가 TEXT_EVENT_STREAM 을 파싱하면 보통 data payload 만 넘어온다.
                // [DONE] 토큰은 그대로 문자열로 온다고 가정한다.
                if ("[DONE]".equals(trimmed)) {
                    return ProovyAiStreamEvent.builder()
                            .event("[DONE]")
                            .data(Collections.emptyMap())
                            .build();
                }

                // 혹시 "data: {..}" 형태로 온 경우를 대비해 prefix 제거
                if (trimmed.startsWith("data:")) {
                    trimmed = trimmed.substring(5).trim();
                }

                // 나머지는 모두 JSON payload 로 간주
                Map<String, Object> payload = objectMapper.readValue(trimmed, Map.class);
                String type = (String) payload.getOrDefault("type", "message");

                return ProovyAiStreamEvent.builder()
                        .event(type)
                        .data(payload)
                        .build();
                        
            } catch (Exception e) {
                log.warn("Failed to parse SSE event: {}", rawEvent, e);
                return ProovyAiStreamEvent.builder()
                        .event("error")
                        .data(Map.of("error", "Failed to parse event"))
                        .build();
            }
        });
    }

    /**
     * 자산 ID → S3 URL 변환
     */
    private List<String> convertAssetIdsToUrls(List<Long> assetIds, Long userId) {
        if (assetIds == null || assetIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Asset> assets = assetRepository.findAllByIdInAndUserId(assetIds, userId);
        
        if (assets.size() != assetIds.size()) {
            log.warn("Some assets not found or unauthorized. Requested: {}, Found: {}", 
                    assetIds.size(), assets.size());
        }

        return assets.stream()
                .map(asset -> s3Service.getFileUrl(asset.getS3Key()))
                .collect(Collectors.toList());
    }

    /**
     * 기능 검증
     */
    private void validateFeatures(List<String> features) {
        for (String feature : features) {
            if (!SUPPORTED_FEATURES.contains(feature)) {
                throw new BusinessException(ErrorCode.CONV4001, 
                        "지원하지 않는 기능: " + feature);
            }
        }
    }

    /**
     * 사용자 메시지 내용을 JSONB 형태로 구성
     */
    private JsonNode buildUserContentJson(ConversationRequest request) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("text", request.getText());
        
        if (request.getLatex() != null && !request.getLatex().isEmpty()) {
            content.put("latex", request.getLatex());
        }
        
        if (request.getChosenFeatures() != null && !request.getChosenFeatures().isEmpty()) {
            content.set("features", objectMapper.valueToTree(request.getChosenFeatures()));
        }
        
        if (request.getMentionedAssetIds() != null && !request.getMentionedAssetIds().isEmpty()) {
            content.set("mentioned_assets", objectMapper.valueToTree(request.getMentionedAssetIds()));
        }
        
        return content;
    }

    /**
     * 메타데이터 구성
     */
    private Map<String, Object> buildMetadata(ConversationRequest request) {
        Map<String, Object> metadata = new HashMap<>();
        
        if (request.getLatex() != null) {
            metadata.put("latex", request.getLatex());
        }
        
        if (request.getCanvasImageIds() != null && !request.getCanvasImageIds().isEmpty()) {
            metadata.put("canvas_image_ids", request.getCanvasImageIds());
        }
        
        return metadata;
    }
}
