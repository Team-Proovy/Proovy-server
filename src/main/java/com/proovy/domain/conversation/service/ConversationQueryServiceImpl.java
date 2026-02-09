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

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageToolRepository messageToolRepository;
    private final MessageAssetRepository messageAssetRepository;
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
        return String.format("users/%d/canvas/%s_%s", userId, uuid, fileName);
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

        // 사용자의 노트 ID 목록 조회
        List<Long> userNoteIds;
        if (noteId != null) {
            // 특정 노트 지정 시 권한 검증
            Note note = noteRepository.findById(noteId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOTE4041));
            if (!note.getUser().getId().equals(userId)) {
                throw new BusinessException(ErrorCode.NOTE4031);
            }
            userNoteIds = List.of(noteId);
        } else {
            userNoteIds = noteRepository.findIdsByUserId(userId);
        }

        if (userNoteIds.isEmpty()) {
            return buildEmptySearchResponse(query, pageable, System.currentTimeMillis() - startTime);
        }

        // Full-Text Search 수행
        List<ConversationSearchResponse.ConversationSearchItem> items =
                searchConversationsWithFullText(userNoteIds, query, toolCode, startDate, endDate, pageable);

        long totalCount = countSearchResults(userNoteIds, query, toolCode, startDate, endDate);
        long searchTimeMs = System.currentTimeMillis() - startTime;

        return ConversationSearchResponse.builder()
                .conversations(items)
                .pageInfo(ConversationSearchResponse.PageInfo.builder()
                        .page(pageable.getPageNumber())
                        .size(pageable.getPageSize())
                        .totalElements(totalCount)
                        .totalPages((int) Math.ceil((double) totalCount / pageable.getPageSize()))
                        .hasNext(pageable.getPageNumber() < (int) Math.ceil((double) totalCount / pageable.getPageSize()) - 1)
                        .build())
                .searchMetadata(ConversationSearchResponse.SearchMetadata.builder()
                        .query(query)
                        .totalMatches(totalCount)
                        .searchTimeMs(searchTimeMs)
                        .build())
                .build();
    }

    private List<ConversationSearchResponse.ConversationSearchItem> searchConversationsWithFullText(
            List<Long> noteIds,
            String query,
            String toolCode,
            LocalDate startDate,
            LocalDate endDate,
            Pageable pageable
    ) {
        // Conversation과 Message를 조인하여 검색
        List<Conversation> conversations = conversationRepository.findByNoteIdIn(noteIds);

        if (conversations.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> conversationIds = conversations.stream()
                .map(Conversation::getId)
                .collect(Collectors.toList());

        // 메시지 조회
        List<Message> messages = messageRepository.findByConversationIdInOrderByCreatedAtAsc(conversationIds);

        // 메시지를 Conversation별로 그룹핑
        Map<Long, List<Message>> messagesByConversation = messages.stream()
                .collect(Collectors.groupingBy(m -> m.getConversation().getId()));

        // 검색어로 필터링 및 결과 생성
        String lowerQuery = query.toLowerCase();
        List<ConversationSearchResponse.ConversationSearchItem> results = new ArrayList<>();

        for (Conversation conv : conversations) {
            List<Message> convMessages = messagesByConversation.getOrDefault(conv.getId(), Collections.emptyList());

            // 날짜 필터링
            if (startDate != null && conv.getCreatedAt().toLocalDate().isBefore(startDate)) {
                continue;
            }
            if (endDate != null && conv.getCreatedAt().toLocalDate().isAfter(endDate)) {
                continue;
            }

            // 사용자 메시지와 어시스턴트 메시지 찾기
            Message userMessage = convMessages.stream()
                    .filter(m -> m.getRole() == MessageRole.USER)
                    .findFirst()
                    .orElse(null);
            Message assistantMessage = convMessages.stream()
                    .filter(m -> m.getRole() == MessageRole.ASSISTANT)
                    .findFirst()
                    .orElse(null);

            // 검색어 매칭 확인
            boolean userMatches = userMessage != null &&
                    userMessage.getContent() != null &&
                    userMessage.getContent().toLowerCase().contains(lowerQuery);
            boolean assistantMatches = assistantMessage != null &&
                    assistantMessage.getContent() != null &&
                    assistantMessage.getContent().toLowerCase().contains(lowerQuery);

            if (!userMatches && !assistantMatches) {
                continue;
            }

            // 도구 코드 필터링
            if (toolCode != null) {
                List<Long> messageIds = convMessages.stream()
                        .map(Message::getId)
                        .collect(Collectors.toList());
                boolean hasToolCode = messageToolRepository.existsByMessageIdInAndToolCode(messageIds, toolCode);
                if (!hasToolCode) {
                    continue;
                }
            }

            // 결과 아이템 생성
            Note note = conv.getNote();

            results.add(ConversationSearchResponse.ConversationSearchItem.builder()
                    .conversationId(conv.getId())
                    .noteId(note.getId())
                    .noteTitle(note.getTitle())
                    .userMessage(buildMessageInfo(userMessage, query))
                    .assistantMessage(buildMessageInfo(assistantMessage, query))
                    .mentionedFiles(getMentionedFiles(convMessages))
                    .mentionedTools(getMentionedTools(convMessages))
                    .relevance(calculateRelevance(userMessage, assistantMessage, query))
                    .createdAt(conv.getCreatedAt())
                    .build());
        }

        // 관련도 순으로 정렬
        results.sort((a, b) -> Double.compare(b.getRelevance(), a.getRelevance()));

        // 페이징 적용
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), results.size());

        if (start >= results.size()) {
            return Collections.emptyList();
        }

        return results.subList(start, end);
    }

    private ConversationSearchResponse.MessageInfo buildMessageInfo(Message message, String query) {
        if (message == null || message.getContent() == null) {
            return null;
        }

        String text = message.getContent();
        String preview = text.length() > 200 ? text.substring(0, 200) + "..." : text;
        String highlight = extractHighlight(text, query);

        return ConversationSearchResponse.MessageInfo.builder()
                .text(text)
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

    private long countSearchResults(
            List<Long> noteIds,
            String query,
            String toolCode,
            LocalDate startDate,
            LocalDate endDate
    ) {
        // 간단한 카운트를 위해 전체 검색 후 카운트
        // 실제 프로덕션에서는 별도의 count 쿼리 최적화 필요
        return searchConversationsWithFullText(noteIds, query, toolCode, startDate, endDate,
                Pageable.unpaged()).size();
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
