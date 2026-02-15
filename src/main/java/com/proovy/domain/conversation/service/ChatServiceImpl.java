package com.proovy.domain.conversation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.conversation.dto.request.ConversationRequest;
import com.proovy.domain.conversation.dto.request.ProovyAiRequest;
import com.proovy.domain.credit.dto.request.CreditUseRequest;
import com.proovy.domain.credit.service.CreditUseService;
import com.proovy.domain.conversation.dto.response.ConversationResponse;
import com.proovy.domain.conversation.dto.response.ProovyAiStreamEvent;
import com.proovy.domain.note.entity.Note;
import com.proovy.domain.note.repository.NoteRepository;
import com.proovy.domain.conversation.entity.ChatMessage;
import com.proovy.domain.conversation.entity.ChatSession;
import com.proovy.domain.conversation.entity.ChatSessionStatus;
import com.proovy.domain.conversation.entity.MessageRole;
import com.proovy.domain.conversation.entity.MessageStatus;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.ConnectException;
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
    private final NoteRepository noteRepository;
    private final S3Service s3Service;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final CreditUseService creditUseService;
    private final TransactionTemplate transactionTemplate;

    @Value("${proovy.ai.host}")
    private String proovyAiHost;

    // 지원되는 기능 목록 (향후 확장 가능)
    private static final Set<String> SUPPORTED_FEATURES = Set.of(
            "Solve", "Check", "Explain", "Variant", "Practice"
    );

    @Override
    public Flux<ProovyAiStreamEvent> streamConversation(Long userId, ConversationRequest request, String accessToken) {
        log.info("[Chat] streamConversation 시작 - userId: {}, textLength: {}, features: {}, assetIds: {}",
            userId,
            request.getText() != null ? request.getText().length() : 0,
            request.getChosenFeatures(),
            request.getMentionedAssetIds());

        // 초기화 작업을 트랜잭션 내에서 수행
        StreamInitData initData = transactionTemplate.execute(status -> {
            // 1. 사용자 검증
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

            // 2. 기능 검증
            if (request.getChosenFeatures() != null) {
                validateFeatures(request.getChosenFeatures());
            }

            // 3. Note 기반 threadId 조회 또는 ChatSession 사용
            Note note = null;
            String threadIdToUse = null;

            if (request.getNoteId() != null) {
                // Note가 지정된 경우
                note = noteRepository.findById(request.getNoteId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOTE4041));

                // Note 소유자 검증
                if (!note.getUser().getId().equals(userId)) {
                    throw new BusinessException(ErrorCode.NOTE4031);
                }

                threadIdToUse = note.getThreadId();
                log.debug("[Chat] Note 기반 대화 - noteId: {}, threadId: {}", note.getId(), threadIdToUse);
            }

            // 4. ChatSession 조회 또는 생성 (Note 없이 대화하거나 메시지 저장용)
            ChatSession chatSession = chatSessionRepository
                    .findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, ChatSessionStatus.ACTIVE)
                    .orElseGet(() -> {
                        ChatSession newSession = ChatSession.builder()
                                .user(user)
                                .status(ChatSessionStatus.ACTIVE)
                                .build();
                        return chatSessionRepository.save(newSession);
                    });

            // Note가 없는 경우 ChatSession의 threadId 사용
            if (threadIdToUse == null && note == null) {
                threadIdToUse = chatSession.getExternalThreadId();
            }

            log.debug("[Chat] 사용 중인 ChatSession - id: {}, externalThreadId: {}, threadIdToUse: {}",
                chatSession.getId(), chatSession.getExternalThreadId(), threadIdToUse);

            // 4. 사용자 메시지 저장 (ChatMessage with Note)
            JsonNode userContentJson = buildUserContentJson(request);
            ChatMessage userMessage = ChatMessage.builder()
                    .chatSession(chatSession)
                    .note(note) // Note 기반 대화인 경우 연결
                    .role(MessageRole.USER)
                    .content(userContentJson)
                    .messageType("text")
                    .status(MessageStatus.COMPLETED)
                    .build();
            chatMessageRepository.save(userMessage);

            // 5. AI 메시지 placeholder 생성 (ChatMessage with Note)
            ObjectNode emptyContent = objectMapper.createObjectNode();
            emptyContent.put("text", "");

            ChatMessage aiMessage = ChatMessage.builder()
                    .chatSession(chatSession)
                    .note(note) // Note 기반 대화인 경우 연결
                    .role(MessageRole.ASSISTANT)
                    .content(emptyContent)
                    .messageType("text")
                    .status(MessageStatus.STREAMING)
                    .build();
            ChatMessage savedAiMessage = chatMessageRepository.save(aiMessage);

            // 6. 자산 URL 변환
            List<String> filesUrl = convertAssetIdsToUrls(request.getMentionedAssetIds(), userId);
            log.info("[Chat] 자산 URL 변환 완료 - assetIds: {}, filesUrl: {}", request.getMentionedAssetIds(), filesUrl);

            return new StreamInitData(chatSession, note, savedAiMessage, threadIdToUse, filesUrl);
        });

        if (initData == null) {
            throw new BusinessException(ErrorCode.CONV5002, "초기화 데이터 생성 실패");
        }

        final ChatSession chatSession = initData.chatSession();
        final Note finalNote = initData.note();
        final Long savedAiMessageId = initData.aiMessage().getId();
        final String finalThreadIdToUse = initData.threadId();
        final List<String> filesUrl = initData.filesUrl();

        // 6.5. Proovy-ai 서버 상태를 사전에 한 번 체크하고, 연결이 불가능하면 바로 CONV5001 비즈니스 예외를 던진다.
        checkProovyAiHealth();

        // 7. Proovy-ai 요청 생성 (Proovy-ai StreamInput 스키마에 맞게 구성)
        ProovyAiRequest aiRequest = ProovyAiRequest.builder()
            .message(request.getText())
            .threadId(finalThreadIdToUse)  // Note 또는 ChatSession의 threadId
            .userId(String.valueOf(userId))
            .filesUrl(filesUrl)
            .chosenFeatures(request.getChosenFeatures())
            .streamTokens(true)
            .agentConfig(buildMetadata(request))
            .authToken(accessToken)  // Spring 인증 토큰 전달
            .build();

        log.info("[Chat] Proovy-ai 요청 생성 - threadId: {}, filesUrl: {}, message: {}",
                finalThreadIdToUse, filesUrl, request.getText());

        // 8. SSE 스트리밍 호출
        final StringBuilder contentBuilder = new StringBuilder();
        final StringBuilder finalMessageHolder = new StringBuilder();
        log.info("[Chat] Proovy-ai 스트리밍 호출 준비 - sessionId: {}, userId: {}",
            chatSession.getId(), userId);

        return callProovyAiStream(aiRequest)
                .doOnNext(event -> {
                    Map<String, Object> data = event.getData();

                    // thread_id 업데이트 (payload 내부에 포함되는 경우)
                    if (data != null && data.containsKey("thread_id")) {
                        String newThreadId = (String) data.get("thread_id");

                        if (newThreadId != null) {
                            // 별도 트랜잭션에서 threadId 업데이트
                            transactionTemplate.executeWithoutResult(status -> {
                                if (finalNote != null) {
                                    // Note가 있으면 Note에 threadId 저장
                                    Note noteToUpdate = noteRepository.findById(finalNote.getId()).orElse(null);
                                    if (noteToUpdate != null && noteToUpdate.getThreadId() == null) {
                                        noteToUpdate.updateThreadId(newThreadId);
                                        noteRepository.save(noteToUpdate);
                                        log.debug("[Chat] Note에 threadId 저장 - noteId: {}, threadId: {}", noteToUpdate.getId(), newThreadId);
                                    }
                                } else {
                                    // Note가 없으면 ChatSession에 저장 (기존 로직)
                                    ChatSession sessionToUpdate = chatSessionRepository.findById(chatSession.getId()).orElse(null);
                                    if (sessionToUpdate != null && sessionToUpdate.getExternalThreadId() == null) {
                                        sessionToUpdate.updateExternalThreadId(newThreadId);
                                        chatSessionRepository.save(sessionToUpdate);
                                        log.debug("[Chat] ChatSession에 threadId 저장 - sessionId: {}, threadId: {}", sessionToUpdate.getId(), newThreadId);
                                    }
                                }
                            });
                        }
                    }

                    // 토큰 스트림(type = token) 기준으로 내용 누적
                    if ("token".equals(event.getEvent()) && data != null) {
                        Object content = data.get("content");
                        if (content != null) {
                            contentBuilder.append(content.toString());
                        }
                    }

                    // message 이벤트 처리 (Python AI의 최종 응답 포함)
                    if ("message".equals(event.getEvent()) && data != null) {
                        Object contentObj = data.get("content");
                        if (contentObj instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> chatMessage = (Map<String, Object>) contentObj;
                            String messageType = Objects.toString(chatMessage.get("type"), null);

                            // AI 메시지의 최종 응답을 저장
                            if ("ai".equals(messageType)) {
                                Object messageContent = chatMessage.get("content");
                                if (messageContent != null) {
                                    String finalText = toPersistableText(messageContent);
                                    finalMessageHolder.setLength(0);
                                    finalMessageHolder.append(finalText);
                                    log.debug("[Chat] 최종 AI 메시지 수신 - length: {}", finalText.length());
                                }
                            }
                        }
                    }
                })
                .doOnComplete(() -> {
                    // 스트리밍 완료 시 최종 내용 저장 - 별도 트랜잭션에서 수행
                    transactionTemplate.executeWithoutResult(status -> {
                        // 우선순위: 1. 최종 AI 메시지 (type=message, ai) 2. 토큰 누적
                        String finalText = finalMessageHolder.length() > 0
                            ? finalMessageHolder.toString()
                            : contentBuilder.toString();

                        if (finalText.isEmpty()) {
                            log.warn("[Chat] 스트리밍 완료했으나 내용이 비어있음 - messageId: {}", savedAiMessageId);
                        }

                        // DB에서 최신 상태로 다시 조회
                        ChatMessage savedAiMessage = chatMessageRepository.findById(savedAiMessageId)
                            .orElseThrow(() -> new BusinessException(ErrorCode.CONV5002, "AI 메시지를 찾을 수 없습니다: " + savedAiMessageId));

                        ObjectNode finalContent = objectMapper.createObjectNode();
                        finalContent.put("text", finalText);
                        savedAiMessage.updateContentAndStatus(finalContent, MessageStatus.COMPLETED);
                        chatMessageRepository.save(savedAiMessage);

                        log.info("[Chat] AI 메시지 저장 완료 - messageId: {}, length: {}",
                            savedAiMessage.getId(), finalText.length());
                    });

                    // 크레딧 차감 (별도 트랜잭션)
                    if (request.getChosenFeatures() != null && !request.getChosenFeatures().isEmpty()) {
                        for (String feature : request.getChosenFeatures()) {
                            try {
                                CreditUseRequest creditRequest = CreditUseRequest.builder()
                                        .eventType("LLM_QUERY")
                                        .featureName(feature)
                                        .difficulty("easy")
                                        .description(feature + " 실행")
                                        .build();
                                creditUseService.useCredit(userId, creditRequest);
                                log.info("크레딧 차감 완료: userId={}, feature={}", userId, feature);
                            } catch (Exception e) {
                                log.error("크레딧 차감 실패: userId={}, feature={}", userId, feature, e);
                            }
                        }
                    }

                    log.info("Streaming completed for session: {}, message: {}",
                            chatSession.getId(), savedAiMessageId);
                })
                .doOnError(error -> {
                    // 에러 발생 시 메시지 삭제 - 별도 트랜잭션에서 수행
                    log.error("Streaming error for session: {}", chatSession.getId(), error);
                    transactionTemplate.executeWithoutResult(status -> {
                        chatMessageRepository.deleteById(savedAiMessageId);
                    });
                })
                .onErrorResume(error -> {
                    log.error("Proovy-ai streaming failed", error);

                    // Proovy-ai 서버 연결 자체가 안 되는 경우 (예: Connection refused)
                    if (error instanceof WebClientRequestException webClientError) {
                        Throwable cause = webClientError.getCause();
                        if (cause instanceof ConnectException) {
                            return Flux.error(new BusinessException(
                                    ErrorCode.CONV5001,
                                    "Proovy-ai 서버에 연결할 수 없습니다: " + cause.getMessage()
                            ));
                        }
                    }

                    // 이미 비즈니스 예외로 래핑된 경우 그대로 전달
                    if (error instanceof BusinessException businessException) {
                        return Flux.error(businessException);
                    }

                    // 그 외 일반적인 스트리밍 오류는 CONV5002로 래핑
                    return Flux.error(new BusinessException(
                            ErrorCode.CONV5002,
                            "스트리밍 중 오류 발생: " + error.getMessage()
                    ));
                });
    }

    @Override
    @Transactional
    public ConversationResponse invokeConversation(Long userId, ConversationRequest request, String accessToken) {
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
     * Proovy-ai /health 헬스 체크를 사전에 수행하여, 서버가 아예 죽어있는 경우에는
     * SSE 스트리밍을 시작하기 전에 즉시 CONV5001 비즈니스 예외를 발생시킨다.
     */
    private void checkProovyAiHealth() {
        String healthUrl = proovyAiHost + "/health";
        log.debug("[Chat] Proovy-ai 헬스 체크 호출: {}", healthUrl);

        try {
            webClient.get()
                    .uri(healthUrl)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(2))
                    .block();
        } catch (Exception e) {
            log.error("[Chat] Proovy-ai 헬스 체크 실패", e);

            if (e instanceof WebClientRequestException webClientError) {
                Throwable cause = webClientError.getCause();
                if (cause instanceof ConnectException) {
                    throw new BusinessException(
                            ErrorCode.CONV5001,
                            "Proovy-ai 서버에 연결할 수 없습니다: " + cause.getMessage()
                    );
                }
            }

            throw new BusinessException(
                    ErrorCode.CONV5001,
                    "Proovy-ai 헬스 체크 중 오류가 발생했습니다: " + e.getMessage()
            );
        }
    }

    /**
     * SSE 이벤트 파싱
     */
    private Mono<ProovyAiStreamEvent> parseSseEvent(String rawEvent) {
        return Mono.fromCallable(() -> {
            try {
                if (rawEvent == null || rawEvent.trim().isEmpty()) {
                    return null; // null 이벤트는 건너뛰기
                }

                String trimmed = rawEvent.trim();

                // [DONE] 토큰 처리
                if ("[DONE]".equals(trimmed)) {
                    return ProovyAiStreamEvent.builder()
                            .event("[DONE]")
                            .data(Collections.emptyMap())
                            .build();
                }

                // "data: {..}" 형태인 경우 prefix 제거
                if (trimmed.startsWith("data:")) {
                    trimmed = trimmed.substring(5).trim();

                    // data: [DONE] 형태도 처리
                    if ("[DONE]".equals(trimmed)) {
                        return ProovyAiStreamEvent.builder()
                                .event("[DONE]")
                                .data(Collections.emptyMap())
                                .build();
                    }
                }

                // JSON payload 파싱
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = objectMapper.readValue(trimmed, Map.class);

                // type 필드 추출 (없으면 "message"로 기본 설정)
                String type = Objects.toString(payload.get("type"), "message");

                log.debug("[Chat] SSE 이벤트 파싱 - type: {}, payload keys: {}", type, payload.keySet());

                return ProovyAiStreamEvent.builder()
                        .event(type)
                        .data(payload)
                        .build();

            } catch (Exception e) {
                log.warn("[Chat] SSE 이벤트 파싱 실패: {}", rawEvent, e);
                return ProovyAiStreamEvent.builder()
                        .event("error")
                        .data(Map.of("error", "Failed to parse event", "raw", rawEvent))
                        .build();
            }
        }).filter(event -> event != null); // null 이벤트 필터링
    }

    private String toPersistableText(Object messageContent) {
        if (messageContent instanceof String text) {
            return text;
        }

        try {
            return objectMapper.writeValueAsString(messageContent);
        } catch (Exception e) {
            log.warn("[Chat] message content JSON 변환 실패, 문자열로 대체", e);
            return String.valueOf(messageContent);
        }
    }

    /**
     * 자산 ID → S3 URL 변환
     */
    private List<String> convertAssetIdsToUrls(List<Long> assetIds, Long userId) {
        if (assetIds == null || assetIds.isEmpty()) {
            log.debug("[Chat] 자산 ID 없음 - assetIds: null or empty");
            return Collections.emptyList();
        }

        List<Asset> assets = assetRepository.findAllByIdInAndUserId(assetIds, userId);
        log.info("[Chat] 자산 조회 결과 - 요청: {}, 조회됨: {}, userId: {}", assetIds, assets.size(), userId);

        if (assets.size() != assetIds.size()) {
            log.warn("[Chat] 일부 자산 조회 실패 - 요청: {}, 조회됨: {}",
                    assetIds.size(), assets.size());
        }

        List<String> urls = assets.stream()
                .map(asset -> {
                    // Presigned URL 생성 (15분 유효)
                    String url = s3Service.generatePresignedDownloadUrl(
                            asset.getS3Key(), asset.getFileName(), 15);
                    log.info("[Chat] Presigned URL 생성 - assetId: {}, s3Key: {}, url: {}",
                            asset.getId(), asset.getS3Key(), url);
                    return url;
                })
                .collect(Collectors.toList());

        return urls;
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

    /**
     * 스트리밍 초기화 데이터를 담는 레코드
     */
    private record StreamInitData(
            ChatSession chatSession,
            Note note,
            ChatMessage aiMessage,
            String threadId,
            List<String> filesUrl
    ) {}
}
