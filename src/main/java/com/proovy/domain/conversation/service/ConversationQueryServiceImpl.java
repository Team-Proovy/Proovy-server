package com.proovy.domain.conversation.service;

import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.entity.AssetStatus;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.conversation.dto.request.CanvasImageUploadRequest;
import com.proovy.domain.conversation.dto.response.CanvasImageUploadResponse;
import com.proovy.domain.conversation.dto.response.ConversationDetailResponse;
import com.proovy.domain.conversation.dto.response.ConversationSearchResponse;
import com.proovy.domain.conversation.entity.Conversation;
import com.proovy.domain.conversation.entity.Message;
import com.proovy.domain.conversation.entity.MessageRole;
import com.proovy.domain.conversation.entity.MessageTool;
import com.proovy.domain.conversation.repository.ConversationRepository;
import com.proovy.domain.conversation.repository.MessageAssetRepository;
import com.proovy.domain.conversation.repository.MessageRepository;
import com.proovy.domain.conversation.repository.MessageToolRepository;
import com.proovy.domain.note.entity.Note;
import com.proovy.domain.note.repository.NoteRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.infra.s3.S3Service;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConversationQueryServiceImpl implements ConversationQueryService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageToolRepository messageToolRepository;
    private final MessageAssetRepository messageAssetRepository;
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

        // PostgreSQL Full-Text Search 실행
        Page<Message> searchResults = executeFullTextSearch(userId, noteId, trimmedQuery, pageable);

        if (searchResults.isEmpty()) {
            return buildEmptySearchResponse(query, pageable, System.currentTimeMillis() - startTime);
        }

        // 검색 결과를 Conversation 기반으로 변환
        List<ConversationSearchResponse.ConversationSearchItem> items =
                buildSearchItemsFromMessages(searchResults.getContent(), trimmedQuery, toolCode, startDate, endDate);

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
     * PostgreSQL Full-Text Search 실행
     * 한글 포함 시 pg_trgm, 그 외는 tsvector 사용
     */
    private Page<Message> executeFullTextSearch(Long userId, Long noteId, String query, Pageable pageable) {
        boolean containsKorean = KOREAN_PATTERN.matcher(query).find();

        if (containsKorean) {
            // 한글 검색: pg_trgm ILIKE + similarity
            if (noteId != null) {
                return messageRepository.searchByTrigramAndNoteId(userId, noteId, query, pageable);
            }
            return messageRepository.searchByTrigram(userId, query, pageable);
        } else {
            // 영문/숫자 검색: tsvector + ts_rank
            if (noteId != null) {
                return messageRepository.searchByFullTextAndNoteId(userId, noteId, query, pageable);
            }
            return messageRepository.searchByFullText(userId, query, pageable);
        }
    }

    /**
     * 메시지 검색 결과를 ConversationSearchItem 목록으로 변환
     */
    private List<ConversationSearchResponse.ConversationSearchItem> buildSearchItemsFromMessages(
            List<Message> messages,
            String query,
            String toolCode,
            LocalDate startDate,
            LocalDate endDate
    ) {
        // Conversation ID로 그룹핑
        Map<Long, List<Message>> messagesByConversation = messages.stream()
                .collect(Collectors.groupingBy(m -> m.getConversation().getId()));

        List<Long> conversationIds = new ArrayList<>(messagesByConversation.keySet());

        // Conversation 일괄 조회
        List<Conversation> conversations = conversationRepository.findAllById(conversationIds);
        Map<Long, Conversation> conversationMap = conversations.stream()
                .collect(Collectors.toMap(Conversation::getId, c -> c));

        // 도구 코드 일괄 조회
        List<Long> allMessageIds = messages.stream().map(Message::getId).collect(Collectors.toList());
        Map<Long, Set<String>> toolCodesByMessageId = new HashMap<>();
        if (!allMessageIds.isEmpty()) {
            List<MessageTool> allTools = messageToolRepository.findByMessageIdIn(allMessageIds);
            for (MessageTool tool : allTools) {
                toolCodesByMessageId
                        .computeIfAbsent(tool.getMessage().getId(), k -> new HashSet<>())
                        .add(tool.getToolCode());
            }
        }

        List<ConversationSearchResponse.ConversationSearchItem> results = new ArrayList<>();

        for (Map.Entry<Long, List<Message>> entry : messagesByConversation.entrySet()) {
            Conversation conv = conversationMap.get(entry.getKey());
            if (conv == null) continue;

            List<Message> convMessages = entry.getValue();

            // 날짜 필터링
            if (startDate != null && conv.getCreatedAt().toLocalDate().isBefore(startDate)) continue;
            if (endDate != null && conv.getCreatedAt().toLocalDate().isAfter(endDate)) continue;

            // 도구 코드 필터링
            if (toolCode != null) {
                boolean hasToolCode = convMessages.stream()
                        .anyMatch(m -> toolCodesByMessageId
                                .getOrDefault(m.getId(), Collections.emptySet())
                                .contains(toolCode));
                if (!hasToolCode) continue;
            }

            Message userMessage = convMessages.stream()
                    .filter(m -> m.getRole() == MessageRole.USER)
                    .findFirst()
                    .orElse(null);

            Message assistantMessage = convMessages.stream()
                    .filter(m -> m.getRole() == MessageRole.ASSISTANT)
                    .findFirst()
                    .orElse(null);

            Note note = conv.getNote();

            results.add(ConversationSearchResponse.ConversationSearchItem.builder()
                    .conversationId(conv.getId())
                    .noteId(note.getId())
                    .noteTitle(note.getTitle())
                    .userMessage(buildMessageInfoWithDbHighlight(userMessage, query))
                    .assistantMessage(buildMessageInfoWithDbHighlight(assistantMessage, query))
                    .mentionedFiles(getMentionedFiles(convMessages))
                    .mentionedTools(getMentionedToolsFromCache(convMessages, toolCodesByMessageId))
                    .relevance(calculateRelevance(userMessage, assistantMessage, query))
                    .createdAt(conv.getCreatedAt())
                    .build());
        }

        return results;
    }

    /**
     * DB 기반 하이라이트 (ts_headline) 사용한 MessageInfo 생성
     */
    private ConversationSearchResponse.MessageInfo buildMessageInfoWithDbHighlight(Message message, String query) {
        if (message == null || message.getContent() == null) {
            return null;
        }

        String text = message.getContent();
        String preview = text.length() > 200 ? text.substring(0, 200) + "..." : text;

        // DB ts_headline 하이라이트 조회 (한글이 아닌 경우에만)
        String highlight;
        boolean containsKorean = KOREAN_PATTERN.matcher(query).find();
        if (!containsKorean) {
            try {
                highlight = messageRepository.getSearchHighlight(message.getId(), query);
            } catch (Exception e) {
                log.debug("ts_headline 조회 실패, 폴백 사용: {}", e.getMessage());
                highlight = extractHighlight(text, query);
            }
        } else {
            highlight = extractHighlight(text, query);
        }

        return ConversationSearchResponse.MessageInfo.builder()
                .text(text)
                .preview(preview)
                .highlight(highlight)
                .build();
    }


    /**
     * 캐시된 도구 코드에서 도구 목록 반환 (N+1 방지)
     */
    private List<String> getMentionedToolsFromCache(List<Message> messages, Map<Long, Set<String>> toolCodesByMessageId) {
        if (toolCodesByMessageId.isEmpty()) {
            return getMentionedTools(messages);
        }

        return messages.stream()
                .flatMap(m -> toolCodesByMessageId.getOrDefault(m.getId(), Collections.emptySet()).stream())
                .distinct()
                .collect(Collectors.toList());
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

    private List<ConversationSearchResponse.MentionedFile> getMentionedFiles(List<Message> messages) {
        List<Long> messageIds = messages.stream()
                .map(Message::getId)
                .collect(Collectors.toList());

        if (messageIds.isEmpty()) {
            return Collections.emptyList();
        }

        return messageAssetRepository.findAssetsByMessageIds(messageIds).stream()
                .map(asset -> ConversationSearchResponse.MentionedFile.builder()
                        .assetId(asset.getId())
                        .fileName(asset.getFileName())
                        .build())
                .collect(Collectors.toList());
    }

    private List<String> getMentionedTools(List<Message> messages) {
        List<Long> messageIds = messages.stream()
                .map(Message::getId)
                .collect(Collectors.toList());

        if (messageIds.isEmpty()) {
            return Collections.emptyList();
        }

        return messageToolRepository.findToolCodesByMessageIds(messageIds);
    }

    private Double calculateRelevance(Message userMessage, Message assistantMessage, String query) {
        double score = 0.0;
        String lowerQuery = query.toLowerCase();

        if (userMessage != null && userMessage.getContent() != null) {
            String content = userMessage.getContent().toLowerCase();
            if (content.contains(lowerQuery)) {
                score += 0.5;
                // 정확한 일치에 가까울수록 더 높은 점수
                int occurrences = countOccurrences(content, lowerQuery);
                score += Math.min(0.3, occurrences * 0.1);
            }
        }

        if (assistantMessage != null && assistantMessage.getContent() != null) {
            String content = assistantMessage.getContent().toLowerCase();
            if (content.contains(lowerQuery)) {
                score += 0.3;
                int occurrences = countOccurrences(content, lowerQuery);
                score += Math.min(0.2, occurrences * 0.05);
            }
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
    public ConversationDetailResponse getConversationDetail(Long userId, Long conversationId) {
        // 1. Conversation 조회
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONV4042));

        // 2. 권한 검증 (해당 대화가 사용자의 노트에 속하는지)
        Note note = conversation.getNote();
        if (!note.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.CONV4031);
        }

        // 3. 메시지 조회
        List<Message> messages = messageRepository.findByConversationId(conversationId);

        Message userMessage = messages.stream()
                .filter(m -> m.getRole() == MessageRole.USER)
                .findFirst()
                .orElse(null);

        Message assistantMessage = messages.stream()
                .filter(m -> m.getRole() == MessageRole.ASSISTANT)
                .findFirst()
                .orElse(null);

        // 4. 응답 생성
        return ConversationDetailResponse.builder()
                .conversationId(conversation.getId())
                .note(ConversationDetailResponse.NoteInfo.builder()
                        .noteId(note.getId())
                        .title(note.getTitle())
                        .build())
                .userMessage(buildUserMessageDetail(userMessage))
                .assistantMessage(buildAssistantMessageDetail(assistantMessage))
                .createdAt(conversation.getCreatedAt())
                .updatedAt(conversation.getCreatedAt()) // Conversation에 updatedAt이 없으면 createdAt 사용
                .build();
    }

    private ConversationDetailResponse.UserMessageDetail buildUserMessageDetail(Message message) {
        if (message == null) {
            return null;
        }

        List<Long> messageIds = List.of(message.getId());

        // 멘션된 파일 조회
        List<Asset> assets = messageAssetRepository.findAssetsByMessageIds(messageIds);
        List<ConversationDetailResponse.MentionedFileDetail> mentionedFiles = assets.stream()
                .map(asset -> ConversationDetailResponse.MentionedFileDetail.builder()
                        .assetId(asset.getId())
                        .fileName(asset.getFileName())
                        .thumbnailUrl(asset.getThumbnailS3Key() != null ?
                                s3Service.getThumbnailUrl(asset.getThumbnailS3Key()) : null)
                        .build())
                .collect(Collectors.toList());

        // 도구 코드 조회
        List<String> toolCodes = messageToolRepository.findToolCodesByMessageIds(messageIds);

        return ConversationDetailResponse.UserMessageDetail.builder()
                .messageId(message.getId())
                .text(message.getContent())
                .mentionedFiles(mentionedFiles)
                .mentionedTools(toolCodes)
                .createdAt(message.getCreatedAt())
                .build();
    }

    private ConversationDetailResponse.AssistantMessageDetail buildAssistantMessageDetail(Message message) {
        if (message == null) {
            return null;
        }

        return ConversationDetailResponse.AssistantMessageDetail.builder()
                .messageId(message.getId())
                .text(message.getContent())
                .createdAt(message.getCreatedAt())
                .build();
    }
}
