package com.proovy.domain.conversation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.entity.AssetStatus;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.conversation.dto.request.CanvasImageUploadRequest;
import com.proovy.domain.conversation.dto.response.CanvasImageUploadResponse;
import com.proovy.domain.conversation.dto.response.ConversationDetailResponse;
import com.proovy.domain.conversation.dto.response.ConversationSearchResponse;
import com.proovy.domain.conversation.entity.ChatMessage;
import com.proovy.domain.conversation.entity.MessageRole;
import com.proovy.domain.conversation.repository.ChatMessageRepository;
import com.proovy.domain.note.entity.Note;
import com.proovy.domain.note.repository.NoteRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.infra.s3.S3Service;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 대화 검색 및 조회 서비스 구현체 (ChatMessage 기반)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConversationQueryServiceImpl implements ConversationQueryService {

    private final ChatMessageRepository chatMessageRepository;
    private final AssetRepository assetRepository;
    private final NoteRepository noteRepository;
    private final S3Service s3Service;

    private static final int PRESIGNED_URL_DURATION_MINUTES = 15;
    private static final Set<String> ALLOWED_CANVAS_MIME_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

    // 한글 검색 패턴 (한글이 포함되어 있으면 pg_trgm 사용)
    private static final Pattern KOREAN_PATTERN = Pattern.compile("[가-힣ㄱ-ㅎㅏ-ㅣ]");

    @Override
    @Transactional
    public CanvasImageUploadResponse uploadCanvasImage(Long userId, CanvasImageUploadRequest request) {
        // 1. MIME 타입 검증 (이미지만 허용)
        if (!ALLOWED_CANVAS_MIME_TYPES.contains(request.getMimeType())) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }

        // 2. 노트 존재 및 권한 검증
        Note note = noteRepository.findById(request.getNoteId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTE4041));

        if (!note.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOTE4031);
        }

        // 3. S3 Key 생성 (캔버스 전용 경로)
        String s3Key = generateCanvasS3Key(userId, request.getFileName());

        // 4. Asset 엔티티 생성
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(PRESIGNED_URL_DURATION_MINUTES);

        Asset asset = Asset.builder()
                .userId(userId)
                .noteId(request.getNoteId())
                .fileName(request.getFileName())
                .fileSize(request.getFileSize())
                .mimeType(request.getMimeType())
                .s3Key(s3Key)
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .uploadExpiresAt(expiresAt)
                .build();

        Asset savedAsset = assetRepository.save(asset);

        // 5. Presigned URL 생성
        String presignedUrl = s3Service.generatePresignedUploadUrl(
                s3Key,
                request.getMimeType(),
                PRESIGNED_URL_DURATION_MINUTES
        );

        log.info("[Canvas] 캔버스 이미지 업로드 URL 발급 - assetId: {}, userId: {}", savedAsset.getId(), userId);

        return CanvasImageUploadResponse.of(savedAsset, presignedUrl);
    }

    private String generateCanvasS3Key(Long userId, String fileName) {
        String uuid = UUID.randomUUID().toString();
        String sanitizedName = sanitizeFileName(fileName);
        return String.format("users/%d/canvas/%s_%s", userId, uuid, sanitizedName);
    }

    /**
     * 파일명 sanitize - 경로 조작 및 부적절한 문자 방지
     */
    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unnamed";
        }

        // 디렉토리 구분자 제거
        String sanitized = fileName.replace("/", "").replace("\\", "");

        // .. 세그먼트 제거
        sanitized = sanitized.replace("..", "");

        // 허용 문자만 유지 (영문, 숫자, 하이픈, 언더스코어, 점, 한글)
        sanitized = sanitized.replaceAll("[^a-zA-Z0-9가-힣._-]", "_");

        // 연속된 언더스코어 정리
        sanitized = sanitized.replaceAll("_+", "_");

        // 앞뒤 언더스코어/점 제거
        sanitized = sanitized.replaceAll("^[_.-]+|[_.-]+$", "");

        // 최대 길이 제한 (100자)
        if (sanitized.length() > 100) {
            sanitized = sanitized.substring(0, 100);
        }

        // 결과가 비어있으면 기본값
        if (sanitized.isBlank()) {
            return "unnamed";
        }

        return sanitized;
    }

    @Override
    public ConversationSearchResponse searchConversations(
            Long userId,
            String query,
            Long noteId,
            String toolCode,
            LocalDate startDate,
            LocalDate endDate,
            Pageable pageable
    ) {
        long startTime = System.currentTimeMillis();

        // 검색어 최소 길이 검증
        if (query == null || query.trim().length() < 2) {
            throw new BusinessException(ErrorCode.STORAGE4003);
        }

        String trimmedQuery = query.trim();

        // 특정 노트 권한 검증
        if (noteId != null) {
            Note note = noteRepository.findById(noteId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOTE4041));
            if (!note.getUser().getId().equals(userId)) {
                throw new BusinessException(ErrorCode.NOTE4031);
            }
        }

        // ChatMessage 기반 검색 실행
        List<ChatMessage> allMessages = chatMessageRepository.findByNoteIdOrderByCreatedAtAsc(noteId);

        // 메모리에서 필터링 및 검색
        List<ConversationSearchResponse.ConversationSearchItem> items =
                searchAndFilterMessages(allMessages, trimmedQuery, startDate, endDate, userId);

        // 페이징 처리
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), items.size());
        List<ConversationSearchResponse.ConversationSearchItem> pagedItems =
                start < items.size() ? items.subList(start, end) : Collections.emptyList();

        long searchTimeMs = System.currentTimeMillis() - startTime;

        return ConversationSearchResponse.builder()
                .conversations(pagedItems)
                .pageInfo(ConversationSearchResponse.PageInfo.builder()
                        .page(pageable.getPageNumber())
                        .size(pageable.getPageSize())
                        .totalElements((long) items.size())
                        .totalPages((int) Math.ceil((double) items.size() / pageable.getPageSize()))
                        .hasNext(end < items.size())
                        .build())
                .searchMetadata(ConversationSearchResponse.SearchMetadata.builder()
                        .query(query)
                        .totalMatches((long) items.size())
                        .searchTimeMs(searchTimeMs)
                        .build())
                .build();
    }

    /**
     * ChatMessage 목록에서 검색어에 매칭되는 대화 추출
     */
    private List<ConversationSearchResponse.ConversationSearchItem> searchAndFilterMessages(
            List<ChatMessage> messages,
            String query,
            LocalDate startDate,
            LocalDate endDate,
            Long userId
    ) {
        List<ConversationSearchResponse.ConversationSearchItem> results = new ArrayList<>();
        ChatMessage userMsg = null;

        for (ChatMessage msg : messages) {
            // 날짜 필터
            if (startDate != null && msg.getCreatedAt().toLocalDate().isBefore(startDate)) continue;
            if (endDate != null && msg.getCreatedAt().toLocalDate().isAfter(endDate)) continue;

            if (msg.getRole() == MessageRole.USER) {
                userMsg = msg;
            } else if (msg.getRole() == MessageRole.ASSISTANT && userMsg != null) {
                // 검색어 매칭 확인
                String userContent = extractTextFromJsonContent(userMsg.getContent());
                String assistantContent = extractTextFromJsonContent(msg.getContent());

                boolean matches = userContent.toLowerCase().contains(query.toLowerCase()) ||
                                assistantContent.toLowerCase().contains(query.toLowerCase());

                if (matches) {
                    Note note = userMsg.getNote();
                    results.add(ConversationSearchResponse.ConversationSearchItem.builder()
                            .conversationId(msg.getId()) // ASSISTANT 메시지 ID를 대화 ID로 사용
                            .noteId(note != null ? note.getId() : null)
                            .noteTitle(note != null ? note.getTitle() : "제목 없음")
                            .userMessage(buildMessageInfo(userMsg, query))
                            .assistantMessage(buildMessageInfo(msg, query))
                            .mentionedFiles(Collections.emptyList()) // TODO: JSONB에서 추출
                            .mentionedTools(Collections.emptyList()) // TODO: JSONB에서 추출
                            .relevance(calculateSimpleRelevance(userContent, assistantContent, query))
                            .createdAt(userMsg.getCreatedAt())
                            .build());
                }

                userMsg = null;
            }
        }

        // 관련도 순으로 정렬
        results.sort((a, b) -> Double.compare(b.getRelevance(), a.getRelevance()));

        return results;
    }

    /**
     * JSONB content에서 text 추출
     */
    private String extractTextFromJsonContent(JsonNode content) {
        if (content == null) return "";
        if (content.has("text")) {
            return content.get("text").asText();
        }
        return content.toString();
    }

    /**
     * ChatMessage를 MessageInfo로 변환
     */
    private ConversationSearchResponse.MessageInfo buildMessageInfo(ChatMessage message, String query) {
        String text = extractTextFromJsonContent(message.getContent());
        String preview = text.length() > 200 ? text.substring(0, 200) + "..." : text;
        String highlight = extractHighlight(text, query);

        return ConversationSearchResponse.MessageInfo.builder()
                .text(text)
                .preview(preview)
                .highlight(highlight)
                .build();
    }

    /**
     * 간단한 관련도 계산 (검색어 출현 빈도 기반)
     */
    private double calculateSimpleRelevance(String userContent, String assistantContent, String query) {
        String combined = (userContent + " " + assistantContent).toLowerCase();
        String lowerQuery = query.toLowerCase();

        int count = 0;
        int index = 0;
        while ((index = combined.indexOf(lowerQuery, index)) != -1) {
            count++;
            index += lowerQuery.length();
        }

        return Math.min(count * 0.2, 1.0); // 최대 1.0
    }

    private String extractHighlight(String text, String query) {
        if (text == null || query == null) {
            return null;
        }

        String lowerText = text.toLowerCase();
        String lowerQuery = query.toLowerCase();
        int index = lowerText.indexOf(lowerQuery);

        if (index == -1) {
            return null;
        }

        int start = Math.max(0, index - 30);
        int end = Math.min(text.length(), index + query.length() + 30);

        String prefix = start > 0 ? "..." : "";
        String suffix = end < text.length() ? "..." : "";

        return prefix + text.substring(start, end) + suffix;
    }
    @Override
    public ConversationDetailResponse getConversationDetail(Long userId, Long conversationId) {
        // conversationId는 실제로 ChatMessage의 ASSISTANT 메시지 ID
        ChatMessage assistantMessage = chatMessageRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONV4042));

        // 권한 검증
        Note note = assistantMessage.getNote();
        if (note == null || !note.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.CONV4031);
        }

        // 같은 Note의 모든 메시지 조회
        List<ChatMessage> allMessages = chatMessageRepository.findByNoteIdOrderByCreatedAtAsc(note.getId());

        // 해당 대화 쌍 찾기 (ASSISTANT 메시지 바로 앞의 USER 메시지)
        ChatMessage userMessage = findPreviousUserMessage(allMessages, assistantMessage);

        // 응답 생성
        return ConversationDetailResponse.builder()
                .conversationId(conversationId)
                .note(ConversationDetailResponse.NoteInfo.builder()
                        .noteId(note.getId())
                        .title(note.getTitle())
                        .build())
                .userMessage(buildUserMessageDetailFromChat(userMessage))
                .assistantMessage(buildAssistantMessageDetailFromChat(assistantMessage))
                .createdAt(userMessage != null ? userMessage.getCreatedAt() : assistantMessage.getCreatedAt())
                .updatedAt(assistantMessage.getCreatedAt())
                .build();
    }

    /**
     * ASSISTANT 메시지 바로 앞의 USER 메시지 찾기
     */
    private ChatMessage findPreviousUserMessage(List<ChatMessage> messages, ChatMessage assistantMsg) {
        ChatMessage prevUser = null;
        for (ChatMessage msg : messages) {
            if (msg.getId().equals(assistantMsg.getId())) {
                return prevUser;
            }
            if (msg.getRole() == MessageRole.USER) {
                prevUser = msg;
            }
        }
        return null;
    }

    private ConversationDetailResponse.UserMessageDetail buildUserMessageDetailFromChat(ChatMessage message) {
        if (message == null) {
            return null;
        }

        String text = extractTextFromJsonContent(message.getContent());

        // TODO: JSONB에서 멘션된 파일 및 도구 추출
        return ConversationDetailResponse.UserMessageDetail.builder()
                .messageId(message.getId())
                .text(text)
                .mentionedFiles(Collections.emptyList())
                .mentionedTools(Collections.emptyList())
                .createdAt(message.getCreatedAt())
                .build();
    }

    private ConversationDetailResponse.AssistantMessageDetail buildAssistantMessageDetailFromChat(ChatMessage message) {
        if (message == null) {
            return null;
        }

        String text = extractTextFromJsonContent(message.getContent());

        return ConversationDetailResponse.AssistantMessageDetail.builder()
                .messageId(message.getId())
                .text(text)
                .createdAt(message.getCreatedAt())
                .build();
    }
}
