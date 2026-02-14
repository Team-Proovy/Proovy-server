package com.proovy.domain.conversation.service;

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
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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

        // ChatMessage 기반 Full-Text Search 실행
        Page<ChatMessage> searchResults = executeChatMessageSearch(
                userId, trimmedQuery, noteId, startDate, endDate, pageable);

        if (searchResults.isEmpty()) {
            return buildEmptySearchResponse(query, pageable, System.currentTimeMillis() - startTime);
        }

        // 검색 결과를 ConversationSearchItem으로 변환
        List<ConversationSearchResponse.ConversationSearchItem> items =
                buildSearchItemsFromChatMessages(searchResults.getContent(), trimmedQuery);

        long searchTimeMs = System.currentTimeMillis() - startTime;

        return ConversationSearchResponse.builder()
                .conversations(items)
                .pageInfo(ConversationSearchResponse.PageInfo.builder()
                        .page(searchResults.getNumber())
                        .size(searchResults.getSize())
                        .totalElements(searchResults.getTotalElements())
                        .totalPages(searchResults.getTotalPages())
                        .hasNext(searchResults.hasNext())
                        .build())
                .searchMetadata(ConversationSearchResponse.SearchMetadata.builder()
                        .query(query)
                        .totalMatches(searchResults.getTotalElements())
                        .searchTimeMs(searchTimeMs)
                        .build())
                .build();
    }

    /**
     * ChatMessage 기반 검색 실행
     * pg_trgm ILIKE 검색으로 통일 (한글/영문 모두 부분 문자열 검색 지원)
     */
    private Page<ChatMessage> executeChatMessageSearch(
            Long userId,
            String query,
            Long noteId,
            LocalDate startDate,
            LocalDate endDate,
            Pageable pageable
    ) {
        // pg_trgm ILIKE 검색 사용 (한글/영문 모두 지원)
        return chatMessageRepository.searchByTrigram(
                userId, query, noteId, startDate, endDate, pageable);
    }

    /**
     * ChatMessage 검색 결과를 ConversationSearchItem 목록으로 변환
     */
    private List<ConversationSearchResponse.ConversationSearchItem> buildSearchItemsFromChatMessages(
            List<ChatMessage> messages,
            String query
    ) {
        if (messages.isEmpty()) {
            return Collections.emptyList();
        }

        List<ConversationSearchResponse.ConversationSearchItem> results = new ArrayList<>();

        for (ChatMessage message : messages) {
            Note note = message.getNote();
            if (note == null) {
                continue; // Note가 없는 메시지는 검색 결과에서 제외
            }

            String textContent = message.getTextContent();
            if (textContent == null || textContent.isBlank()) {
                continue; // 텍스트가 없는 메시지는 검색 결과에서 제외
            }

            ConversationSearchResponse.MessageInfo messageInfo =
                    buildMessageInfoFromChatMessage(message, query);

            // 빈 MessageInfo (null 방지)
            ConversationSearchResponse.MessageInfo emptyMessageInfo =
                    ConversationSearchResponse.MessageInfo.builder()
                            .text("")
                            .preview("")
                            .highlight("")
                            .build();

            // 메시지 역할에 따라 userMessage 또는 assistantMessage 설정
            ConversationSearchResponse.ConversationSearchItem.ConversationSearchItemBuilder builder =
                    ConversationSearchResponse.ConversationSearchItem.builder()
                            .conversationId(message.getId()) // ChatMessage ID를 conversationId로 사용
                            .noteId(note.getId())
                            .noteTitle(note.getTitle())
                            .mentionedFiles(Collections.emptyList())
                            .mentionedTools(Collections.emptyList())
                            .relevance(calculateRelevance(textContent, query))
                            .createdAt(message.getCreatedAt());

            if (message.getRole() == MessageRole.USER) {
                builder.userMessage(messageInfo);
                builder.assistantMessage(emptyMessageInfo);
            } else if (message.getRole() == MessageRole.ASSISTANT) {
                builder.userMessage(emptyMessageInfo);
                builder.assistantMessage(messageInfo);
            }

            results.add(builder.build());
        }

        return results;
    }

    /**
     * ChatMessage에서 MessageInfo 생성
     */
    private ConversationSearchResponse.MessageInfo buildMessageInfoFromChatMessage(ChatMessage message, String query) {
        String textContent = message.getTextContent();
        if (textContent == null) {
            return null;
        }

        String preview = textContent.length() > 200 ? textContent.substring(0, 200) + "..." : textContent;

        // 검색어 주변 하이라이트 추출
        String highlight = extractHighlight(textContent, query);

        return ConversationSearchResponse.MessageInfo.builder()
                .text(textContent)
                .preview(preview)
                .highlight(highlight)
                .build();
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

    private Double calculateRelevance(String content, String query) {
        if (content == null || query == null) {
            return 0.0;
        }

        double score = 0.0;
        String lowerContent = content.toLowerCase();
        String lowerQuery = query.toLowerCase();

        if (lowerContent.contains(lowerQuery)) {
            score += 0.5;
            int occurrences = countOccurrences(lowerContent, lowerQuery);
            score += Math.min(0.5, occurrences * 0.1);
        }

        return Math.min(1.0, score);
    }

    private int countOccurrences(String text, String query) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(query, index)) != -1) {
            count++;
            index += query.length();
        }
        return count;
    }

    private ConversationSearchResponse buildEmptySearchResponse(String query, Pageable pageable, long searchTimeMs) {
        return ConversationSearchResponse.builder()
                .conversations(Collections.emptyList())
                .pageInfo(ConversationSearchResponse.PageInfo.builder()
                        .page(pageable.getPageNumber())
                        .size(pageable.getPageSize())
                        .totalElements(0)
                        .totalPages(0)
                        .hasNext(false)
                        .build())
                .searchMetadata(ConversationSearchResponse.SearchMetadata.builder()
                        .query(query)
                        .totalMatches(0)
                        .searchTimeMs(searchTimeMs)
                        .build())
                .build();
    }

    @Override
    public ConversationDetailResponse getConversationDetail(Long userId, Long chatMessageId) {
        // 1. ChatMessage 조회 (이제 conversationId 대신 chatMessageId를 사용)
        ChatMessage chatMessage = chatMessageRepository.findById(chatMessageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONV4042));

        // 2. 권한 검증 (해당 대화가 사용자의 노트에 속하는지)
        Note note = chatMessage.getNote();
        if (note == null) {
            throw new BusinessException(ErrorCode.CONV4042);
        }
        
        if (!note.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.CONV4031);
        }

        String textContent = chatMessage.getTextContent();

        // 3. 응답 생성
        ConversationDetailResponse.ConversationDetailResponseBuilder responseBuilder = 
                ConversationDetailResponse.builder()
                        .conversationId(chatMessage.getId())
                        .note(ConversationDetailResponse.NoteInfo.builder()
                                .noteId(note.getId())
                                .title(note.getTitle())
                                .build())
                        .createdAt(chatMessage.getCreatedAt())
                        .updatedAt(chatMessage.getCreatedAt());

        if (chatMessage.getRole() == MessageRole.USER) {
            responseBuilder.userMessage(ConversationDetailResponse.UserMessageDetail.builder()
                    .messageId(chatMessage.getId())
                    .text(textContent)
                    .mentionedFiles(Collections.emptyList())
                    .mentionedTools(Collections.emptyList())
                    .createdAt(chatMessage.getCreatedAt())
                    .build());
        } else if (chatMessage.getRole() == MessageRole.ASSISTANT) {
            responseBuilder.assistantMessage(ConversationDetailResponse.AssistantMessageDetail.builder()
                    .messageId(chatMessage.getId())
                    .text(textContent)
                    .createdAt(chatMessage.getCreatedAt())
                    .build());
        }

        return responseBuilder.build();
    }
}
