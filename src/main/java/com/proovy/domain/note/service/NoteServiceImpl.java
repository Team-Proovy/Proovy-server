package com.proovy.domain.note.service;

import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.entity.FileCategory;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.conversation.entity.*;
import com.proovy.domain.conversation.repository.*;
import com.proovy.domain.note.dto.request.CreateNoteRequest;
import com.proovy.domain.note.dto.request.UpdateNoteTitleRequest;
import com.proovy.domain.note.dto.response.CreateNoteResponse;
import com.proovy.domain.note.dto.response.DeleteNoteResponse;
import com.proovy.domain.note.dto.response.NoteDetailResponse;
import com.proovy.domain.note.dto.response.DeleteNoteResponse;
import com.proovy.domain.note.dto.response.NoteListResponse;
import com.proovy.domain.note.dto.response.UpdateNoteTitleResponse;
import com.proovy.domain.note.dto.response.AssetListResponse;
import com.proovy.domain.note.entity.Note;
import com.proovy.domain.note.repository.NoteRepository;
import com.proovy.domain.embedding.service.EmbeddingJobPublisher;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import com.proovy.global.util.HashUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.proovy.global.infra.s3.S3Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NoteServiceImpl implements NoteService {

    private final NoteRepository noteRepository;
    private final UserRepository userRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MessageAssetRepository messageAssetRepository;
    private final MessageToolRepository messageToolRepository;
    private final AssetRepository assetRepository;
    private final com.proovy.domain.user.repository.UserPlanRepository userPlanRepository;
    private final S3Service s3Service;
    private final EmbeddingJobPublisher embeddingJobPublisher;

    @Override
    public CreateNoteResponse createNote(Long userId, CreateNoteRequest request) {
                log.info("노트 생성 요청 - userId: {}, title: {}", userId, request.title());

        // 1. 사용자 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        // 2. 노트 생성 한도 체크
        // 사용자의 활성 플랜 조회 (없으면 FREE 플랜으로 간주)
        com.proovy.domain.user.entity.PlanType planType = userPlanRepository.findActivePlanTypeByUserId(userId)
                .orElse(com.proovy.domain.user.entity.PlanType.FREE);

        long noteCount = noteRepository.countByUserId(userId);
        int noteLimit = planType.getNoteLimit();

        if (noteCount >= noteLimit) {
            throw new BusinessException(ErrorCode.NOTE4031);
        }

        // 3. 노트 제목 결정 (요청에 제목이 없으면 더미 제목 생성)
        String resolvedTitle = generateNoteTitle(request.title());
        Note note = Note.builder()
                .user(user)
                .title(resolvedTitle)
                .contentMd("")
                .build();
        note = noteRepository.save(note);
        log.info("노트 생성 완료 - noteId: {}", note.getId());

        String contentHash = HashUtils.sha256(note.getContentMd());
        embeddingJobPublisher.publishEmbeddingJob(note.getId(), contentHash, "text-embedding-3-small");

        // 4. 응답 생성 (대화/메시지는 생성하지 않음)
        int conversationLimit = 50; // TODO: PlanType에 대화 제한 수가 추가되면 해당 값 사용
        String titleGeneratedBy = (request.title() == null || request.title().isBlank()) ? "SYSTEM" : "USER";

        return new CreateNoteResponse(
                note.getId(),
                note.getTitle(),
                titleGeneratedBy,
                conversationLimit,
                null,
                note.getCreatedAt()
        );
    }

    /**
     * 노트 제목 생성
     * - 요청에 제목이 있으면 공백/길이만 정리하여 사용
     * - 없으면 현재 시각 기반의 더미 제목 생성
     */
    private String generateNoteTitle(String rawTitle) {
        if (rawTitle != null && !rawTitle.isBlank()) {
            String normalized = rawTitle.replaceAll("\\s+", " ").trim();
            if (normalized.length() > 200) {
                normalized = normalized.substring(0, 200);
            }
            return normalized;
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return "새 노트 " + timestamp;
    }

    @Override
    @Transactional(readOnly = true)
    public NoteListResponse getNoteList(Long userId, int page, int size, String sort) {
        log.info("노트 목록 조회 요청 - userId: {}, page: {}, size: {}, sort: {}", userId, page, size, sort);

        // 1. 페이지/사이즈 하한 및 상한 보정
        page = Math.max(page, 0);
        size = Math.min(Math.max(size, 1), 50);

        // 2. 정렬 파라미터 파싱
        Sort sortOrder = parseSortParameter(sort);

        // 3. 페이지 요청 생성
        Pageable pageable = PageRequest.of(page, size, sortOrder);

        // 4. 노트 페이지 조회
        Page<Note> notePage = noteRepository.findByUserId(userId, pageable);

        // 5. 사용자의 활성 플랜 조회 (대화 제한 수 계산용)
        int conversationLimit = 50; // 기본값

        // 6. 노트 ID 목록 추출
        List<Long> noteIds = notePage.getContent().stream()
                .map(Note::getId)
                .collect(Collectors.toList());

        // 7. 배치 쿼리로 대화(메시지) 개수 조회 (N+1 문제 해결)
        java.util.Map<Long, Long> conversationCountMap = new java.util.HashMap<>();
        if (!noteIds.isEmpty()) {
            List<Object[]> messageCounts = chatMessageRepository.countByNoteIdIn(noteIds);
            for (Object[] row : messageCounts) {
                Long noteId = ((Number) row[0]).longValue();
                Long count = ((Number) row[1]).longValue();
                // 메시지 쌍(USER + ASSISTANT)을 대화로 계산
                conversationCountMap.put(noteId, count / 2);
            }
        }

        // 8. 배치 쿼리로 자산 개수 조회 (N+1 문제 해결)
        java.util.Map<Long, Long> assetCountMap = new java.util.HashMap<>();
        if (!noteIds.isEmpty()) {
            List<java.util.Map<String, Object>> assetCounts =
                assetRepository.countByNoteIdIn(noteIds);
            for (java.util.Map<String, Object> row : assetCounts) {
                Long noteId = ((Number) row.get("noteId")).longValue();
                Long count = ((Number) row.get("count")).longValue();
                assetCountMap.put(noteId, count);
            }
        }

        // 9. DTO 변환 (미리 조회한 카운트 사용)
        List<NoteListResponse.NoteDto> noteDtos = notePage.getContent().stream()
                .map(note -> buildNoteDto(
                    note,
                    conversationLimit,
                    conversationCountMap.getOrDefault(note.getId(), 0L),
                    assetCountMap.getOrDefault(note.getId(), 0L)
                ))
                .collect(Collectors.toList());

        // 10. PageInfo 생성
        NoteListResponse.PageInfo pageInfo = NoteListResponse.PageInfo.builder()
                .page(notePage.getNumber())
                .size(notePage.getSize())
                .totalElements(notePage.getTotalElements())
                .totalPages(notePage.getTotalPages())
                .hasNext(notePage.hasNext())
                .hasPrevious(notePage.hasPrevious())
                .build();

        log.info("노트 목록 조회 완료 - 총 {}개 조회", noteDtos.size());

        return NoteListResponse.builder()
                .notes(noteDtos)
                .pageInfo(pageInfo)
                .build();
    }

    /**
     * 정렬 파라미터 파싱
     * 예: "lastUsedAt,desc" -> Sort.by(Sort.Direction.DESC, "updatedAt")
     * API 스펙상 lastUsedAt이지만 실제로는 updatedAt 컬럼을 사용
     */
    private Sort parseSortParameter(String sort) {
        if (sort == null || sort.isEmpty()) {
            return Sort.by(Sort.Direction.DESC, "updatedAt");
        }

        String[] parts = sort.split(",");
        if (parts.length != 2) {
            return Sort.by(Sort.Direction.DESC, "updatedAt");
        }

        String property = parts[0].trim();
        String direction = parts[1].trim();

        // lastUsedAt -> updatedAt으로 매핑
        if ("lastUsedAt".equals(property)) {
            property = "updatedAt";
        }

        // 허용된 정렬 필드만 사용
        if (!Set.of("updatedAt", "createdAt", "title").contains(property)) {
            property = "updatedAt";
        }

        Sort.Direction sortDirection = direction.equalsIgnoreCase("asc")
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        return Sort.by(sortDirection, property);
    }

    /**
     * Note 엔티티를 NoteDto로 변환
     * updatedAt을 lastUsedAt으로 매핑하여 반환
     * @param note 노트 엔티티
     * @param conversationLimit 대화 제한 수
     * @param conversationCount 대화 개수 (미리 조회됨)
     * @param assetCount 자산 개수 (미리 조회됨)
     */
    private NoteListResponse.NoteDto buildNoteDto(
            Note note,
            int conversationLimit,
            long conversationCount,
            long assetCount
    ) {
        // 대화 사용률 계산
        int conversationUsagePercent = conversationLimit > 0
                ? Math.round((float) conversationCount / conversationLimit * 100)
                : 0;

        return NoteListResponse.NoteDto.builder()
                .noteId(note.getId())
                .title(note.getTitle())
                .thumbnailUrl(null) // TODO: 썸네일 기능 구현 시 추가
                .conversationCount((int) conversationCount)
                .conversationLimit(conversationLimit)
                .conversationUsagePercent(conversationUsagePercent)
                .assetCount((int) assetCount)
                .createdAt(note.getCreatedAt())
                .lastUsedAt(note.getUpdatedAt()) // updatedAt을 lastUsedAt으로 매핑
                .build();
    }

    @Override
    public UpdateNoteTitleResponse updateNoteTitle(Long userId, Long noteId, UpdateNoteTitleRequest request) {
        log.info("노트 제목 수정 요청 - userId: {}, noteId: {}, newTitle: {}", userId, noteId, request.title());

        // 1. 노트 조회
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTE4041));

        // 2. 권한 확인 (노트 소유자가 맞는지)
        if (!note.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOTE4031);
        }

        // 3. 제목 업데이트
        note.updateTitle(request.title());
        note = noteRepository.save(note);

        log.info("노트 제목 수정 완료 - noteId: {}", noteId);

        // 4. Response 생성
        return UpdateNoteTitleResponse.builder()
                .noteId(note.getId())
                .title(note.getTitle())
                .updatedAt(note.getUpdatedAt())
                .build();
    }

    @Override
    public DeleteNoteResponse deleteNote(Long userId, Long noteId) {
        log.info("노트 삭제 요청 - userId: {}, noteId: {}", userId, noteId);

        // 1. 노트 조회 및 권한 확인
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTE4041));

        if (!note.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOTE4031);
        }

        // 2. 통계용 정보 수집 - ChatMessage 개수 (메시지 쌍을 대화로 계산)
        long messageCount = chatMessageRepository.countByNoteId(noteId);
        long conversationCount = messageCount / 2;

        // 3. S3 삭제를 위한 Asset 정보만 조회 (영속성 컨텍스트 오염 방지를 위해 별도 처리)
        List<Asset> assets = assetRepository.findAllByNoteId(noteId);
        int assetCount = assets.size();

        List<String> s3KeysToDelete = new ArrayList<>();
        long freedStorageBytes = 0L;

        for (Asset asset : assets) {
            if (asset.getS3Key() != null) {
                s3KeysToDelete.add(asset.getS3Key());
                freedStorageBytes += asset.getFileSize();
            }
            if (asset.getThumbnailS3Key() != null) {
                s3KeysToDelete.add(asset.getThumbnailS3Key());
            }
        }

        // 4. Asset ID 목록 추출 (JPQL 벌크 삭제용)
        List<Long> assetIds = assets.stream()
                .map(Asset::getId)
                .collect(Collectors.toList());

        // 5. S3 파일 삭제
        if (!s3KeysToDelete.isEmpty()) {
            s3Service.deleteFiles(s3KeysToDelete);
            log.info("S3 파일 삭제 완료 - {} 개 파일", s3KeysToDelete.size());
        }

        // 6. ChatMessage ID 목록 조회
        List<Long> chatMessageIds = chatMessageRepository.findIdsByNoteId(noteId);

        // ========== JPQL 벌크 삭제 시작 (영속성 컨텍스트를 거치지 않음) ==========

        // 7. MessageAsset 삭제 (Asset 기준 + ChatMessage 기준 모두)
        if (!assetIds.isEmpty()) {
            messageAssetRepository.deleteByAssetIdInBulk(assetIds);
            log.info("Asset 관련 MessageAsset 삭제 완료");
        }
        if (!chatMessageIds.isEmpty()) {
            messageAssetRepository.deleteByChatMessageIdInBulk(chatMessageIds);
            log.info("ChatMessage 관련 MessageAsset 삭제 완료");
        }

        // 8. MessageTool 삭제
        if (!chatMessageIds.isEmpty()) {
            messageToolRepository.deleteByChatMessageIdInBulk(chatMessageIds);
            log.info("MessageTool 삭제 완료");
        }

        // 9. ChatMessage 삭제
        chatMessageRepository.deleteByNoteIdInBulk(noteId);
        log.info("ChatMessage 삭제 완료 - {} 개", messageCount);

        // 10. Asset 삭제
        assetRepository.deleteByNoteIdInBulk(noteId);
        log.info("자산 삭제 완료 - {} 개", assetCount);

        // 11. Note 삭제 (이제 안전하게 삭제 가능)
        noteRepository.deleteById(noteId);

        log.info("노트 삭제 완료 - noteId: {}, conversations: {}, assets: {}, freedStorage: {} bytes",
                noteId, conversationCount, assetCount, freedStorageBytes);

        return DeleteNoteResponse.builder()
                .deletedNoteId(noteId)
                .deletedConversationCount((int) conversationCount)
                .deletedAssetCount(assetCount)
                .freedStorageBytes(freedStorageBytes)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public NoteDetailResponse getNoteDetail(Long userId, Long noteId, int conversationPage, int conversationSize) {
        log.info("노트 상세 조회 요청 - userId: {}, noteId: {}, page: {}, size: {}",
                userId, noteId, conversationPage, conversationSize);

        // 1. 노트 존재 확인
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTE4041));

        // 2. 권한 검증
        if (!note.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOTE4031);
        }

        // 3. 사용자 플랜 정보 조회 (대화 제한 수 계산)
        com.proovy.domain.user.entity.PlanType planType = userPlanRepository.findActivePlanTypeByUserId(userId)
                .orElse(com.proovy.domain.user.entity.PlanType.FREE);
        int conversationLimit = 50; // 기본 대화 제한 수

        // 4. 전체 메시지 개수 조회 (대화 쌍으로 나눠서 계산)
        long totalMessages = chatMessageRepository.countByNoteId(noteId);
        long totalConversations = totalMessages / 2;

        // 5. 노트의 모든 ChatMessage 조회 (시간순)
        List<ChatMessage> allMessages = chatMessageRepository.findByNoteIdOrderByCreatedAtAsc(noteId);

        // 6. USER + ASSISTANT 쌍으로 대화 그룹화
        List<ChatMessagePair> conversationPairs = groupMessagesIntoConversations(allMessages);

        // 7. 페이징 적용
        int startIndex = conversationPage * conversationSize;
        int endIndex = Math.min(startIndex + conversationSize, conversationPairs.size());
        List<ChatMessagePair> pagedPairs = (startIndex < conversationPairs.size())
                ? conversationPairs.subList(startIndex, endIndex)
                : List.of();

        // 8. 메시지 ID 목록 추출
        List<Long> chatMessageIds = pagedPairs.stream()
                .flatMap(pair -> {
                    List<Long> ids = new ArrayList<>();
                    if (pair.userMessage != null) ids.add(pair.userMessage.getId());
                    if (pair.assistantMessage != null) ids.add(pair.assistantMessage.getId());
                    return ids.stream();
                })
                .toList();

        // 9. 메시지-자산 연결 조회
        Map<Long, List<Asset>> messageAssetMap = new HashMap<>();
        if (!chatMessageIds.isEmpty()) {
            List<MessageAsset> messageAssets = messageAssetRepository.findByChatMessageIdIn(chatMessageIds);
            messageAssetMap = messageAssets.stream()
                    .collect(Collectors.groupingBy(
                            ma -> ma.getChatMessage().getId(),
                            Collectors.mapping(MessageAsset::getAsset, Collectors.toList())
                    ));
        }

        // 10. 메시지-도구 연결 조회
        Map<Long, List<String>> messageToolMap = new HashMap<>();
        if (!chatMessageIds.isEmpty()) {
            List<MessageTool> messageTools = messageToolRepository.findByChatMessageIdIn(chatMessageIds);
            messageToolMap = messageTools.stream()
                    .collect(Collectors.groupingBy(
                            mt -> mt.getChatMessage().getId(),
                            Collectors.mapping(MessageTool::getToolCode, Collectors.toList())
                    ));
        }

        // 11. 노트의 모든 자산 조회
        List<Asset> noteAssets = assetRepository.findAllByNoteId(noteId);

        // 12. 자산 정보 DTO 생성
        List<NoteDetailResponse.AssetInfo> assetInfos = noteAssets.stream()
                .map(asset -> {
                    String thumbnailUrl = asset.getThumbnailS3Key() != null
                            ? s3Service.getThumbnailUrl(asset.getThumbnailS3Key())
                            : null;
                    FileCategory category = FileCategory.fromMimeType(asset.getMimeType());

                    return NoteDetailResponse.AssetInfo.builder()
                            .assetId(asset.getId())
                            .fileName(asset.getFileName())
                            .fileType(category.getValue().toUpperCase())
                            .fileSize(asset.getFileSize())
                            .ocrStatus(asset.getOcrStatus() != null ? asset.getOcrStatus().name().toLowerCase() : "pending")
                            .thumbnailUrl(thumbnailUrl)
                            .createdAt(asset.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());

        // 13. 대화 정보 DTO 생성 (역순으로 정렬하여 최신 대화가 먼저 오도록)
        final Map<Long, List<Asset>> finalMessageAssetMap = messageAssetMap;
        final Map<Long, List<String>> finalMessageToolMap = messageToolMap;

        List<NoteDetailResponse.ConversationInfo> conversationInfos = pagedPairs.stream()
                .sorted((a, b) -> {
                    LocalDateTime aTime = a.userMessage != null ? a.userMessage.getCreatedAt() : LocalDateTime.MIN;
                    LocalDateTime bTime = b.userMessage != null ? b.userMessage.getCreatedAt() : LocalDateTime.MIN;
                    return bTime.compareTo(aTime); // 최신순
                })
                .map(pair -> {
                    Long conversationId = pair.userMessage != null ? pair.userMessage.getId() : 
                            (pair.assistantMessage != null ? pair.assistantMessage.getId() : 0L);
                    LocalDateTime createdAt = pair.userMessage != null ? pair.userMessage.getCreatedAt() :
                            (pair.assistantMessage != null ? pair.assistantMessage.getCreatedAt() : LocalDateTime.now());

                    // 빈 MessageInfo (null 방지)
                    NoteDetailResponse.MessageInfo emptyMessageInfo = NoteDetailResponse.MessageInfo.builder()
                            .messageId(0L)
                            .content("")
                            .mentionedAssets(List.of())
                            .mentionedTools(List.of())
                            .usedTools(List.of())
                            .generatedFiles(List.of())
                            .createdAt(LocalDateTime.now())
                            .build();

                    return NoteDetailResponse.ConversationInfo.builder()
                            .conversationId(conversationId)
                            .userMessage(pair.userMessage != null ?
                                    buildChatMessageInfo(pair.userMessage, finalMessageAssetMap, finalMessageToolMap, true) : emptyMessageInfo)
                            .assistantMessage(pair.assistantMessage != null ?
                                    buildChatMessageInfo(pair.assistantMessage, finalMessageAssetMap, finalMessageToolMap, false) : emptyMessageInfo)
                            .createdAt(createdAt)
                            .build();
                })
                .collect(Collectors.toList());

        // 14. 사용량 정보 생성
        int conversationUsagePercent = conversationLimit > 0
                ? (int) Math.round((double) totalConversations / conversationLimit * 100)
                : 0;

        NoteDetailResponse.UsageInfo usageInfo = NoteDetailResponse.UsageInfo.builder()
                .conversationCount((int) totalConversations)
                .conversationLimit(conversationLimit)
                .conversationUsagePercent(conversationUsagePercent)
                .build();

        // 15. 페이지 정보 생성
        int totalPages = (int) Math.ceil((double) conversationPairs.size() / conversationSize);
        NoteDetailResponse.PageInfo pageInfo = NoteDetailResponse.PageInfo.builder()
                .page(conversationPage)
                .size(conversationSize)
                .totalElements((long) conversationPairs.size())
                .totalPages(totalPages)
                .hasNext(conversationPage < totalPages - 1)
                .build();

        // 16. lastUsedAt 계산 (updatedAt 또는 createdAt 사용)
        LocalDateTime lastUsedAt = note.getUpdatedAt() != null ? note.getUpdatedAt() : note.getCreatedAt();

        return NoteDetailResponse.builder()
                .noteId(note.getId())
                .title(note.getTitle())
                .usage(usageInfo)
                .assets(assetInfos)
                .conversations(conversationInfos)
                .conversationPageInfo(pageInfo)
                .createdAt(note.getCreatedAt())
                .lastUsedAt(lastUsedAt)
                .build();
    }

    /**
     * 메시지를 USER + ASSISTANT 쌍으로 그룹화
     */
    private List<ChatMessagePair> groupMessagesIntoConversations(List<ChatMessage> messages) {
        List<ChatMessagePair> pairs = new ArrayList<>();
        ChatMessage pendingUserMessage = null;

        for (ChatMessage msg : messages) {
            if (msg.getRole() == MessageRole.USER) {
                // 이전에 매칭되지 않은 USER 메시지가 있으면 단독으로 저장
                if (pendingUserMessage != null) {
                    pairs.add(new ChatMessagePair(pendingUserMessage, null));
                }
                pendingUserMessage = msg;
            } else if (msg.getRole() == MessageRole.ASSISTANT) {
                if (pendingUserMessage != null) {
                    pairs.add(new ChatMessagePair(pendingUserMessage, msg));
                    pendingUserMessage = null;
                } else {
                    // USER 없이 ASSISTANT만 있는 경우
                    pairs.add(new ChatMessagePair(null, msg));
                }
            }
        }

        // 마지막으로 남은 USER 메시지 처리
        if (pendingUserMessage != null) {
            pairs.add(new ChatMessagePair(pendingUserMessage, null));
        }

        return pairs;
    }

    /**
     * USER + ASSISTANT 메시지 쌍 내부 클래스
     */
    private static class ChatMessagePair {
        final ChatMessage userMessage;
        final ChatMessage assistantMessage;

        ChatMessagePair(ChatMessage userMessage, ChatMessage assistantMessage) {
            this.userMessage = userMessage;
            this.assistantMessage = assistantMessage;
        }
    }

    private NoteDetailResponse.MessageInfo buildChatMessageInfo(
            ChatMessage chatMessage,
            Map<Long, List<Asset>> messageAssetMap,
            Map<Long, List<String>> messageToolMap,
            boolean isUserMessage) {

        List<Asset> assets = messageAssetMap.getOrDefault(chatMessage.getId(), List.of());
        List<String> tools = messageToolMap.getOrDefault(chatMessage.getId(), List.of());

        // content에서 텍스트 추출
        String content = chatMessage.getTextContent();

        // 멘션된 자산 정보
        List<NoteDetailResponse.MentionedAsset> mentionedAssets = null;
        if (isUserMessage && !assets.isEmpty()) {
            mentionedAssets = assets.stream()
                    .map(asset -> NoteDetailResponse.MentionedAsset.builder()
                            .assetId(asset.getId())
                            .fileName(asset.getFileName())
                            .build())
                    .collect(Collectors.toList());
        }

        // AI가 생성한 파일 정보
        List<NoteDetailResponse.GeneratedFile> generatedFiles = null;
        if (!isUserMessage) {
            generatedFiles = assets.stream()
                    .filter(asset -> asset.getSource() == Asset.AssetSource.ai_generated)
                    .map(asset -> NoteDetailResponse.GeneratedFile.builder()
                            .fileId(asset.getId())
                            .fileName(asset.getFileName())
                            .fileType("SOLUTION")
                            .downloadUrl(s3Service.getFileUrl(asset.getS3Key()))
                            .build())
                    .collect(Collectors.toList());
            if (generatedFiles.isEmpty()) {
                generatedFiles = null;
            }
        }

        return NoteDetailResponse.MessageInfo.builder()
                .messageId(chatMessage.getId())
                .content(content)
                .mentionedAssets(mentionedAssets)
                .mentionedTools(isUserMessage && !tools.isEmpty() ? tools : null)
                .usedTools(!isUserMessage && !tools.isEmpty() ? tools : null)
                .generatedFiles(generatedFiles)
                .createdAt(chatMessage.getCreatedAt())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public AssetListResponse getAssetList(Long userId, Long noteId, String query) {
        // 1. 노트 존재 및 권한 확인
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTE4041));

        if (!note.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOTE4031);
        }

        // 2. 자산 목록 조회 (검색어가 있으면 파일명으로 필터링)
        List<Asset> assets;
        if (query != null && !query.trim().isEmpty()) {
            assets = assetRepository.findAllByNoteIdAndFileNameContainingIgnoreCase(noteId, query.trim());
        } else {
            assets = assetRepository.findAllByNoteId(noteId);
        }

        // 3. 자산 정보 DTO 생성
        List<AssetListResponse.AssetInfo> assetInfos = assets.stream()
                .map(asset -> {
                    String thumbnailUrl = asset.getThumbnailS3Key() != null
                            ? s3Service.getThumbnailUrl(asset.getThumbnailS3Key())
                            : null;
                    FileCategory category = FileCategory.fromMimeType(asset.getMimeType());

                    return AssetListResponse.AssetInfo.builder()
                            .assetId(asset.getId())
                            .fileName(asset.getFileName())
                            .fileSize(asset.getFileSize())
                            .mimeType(asset.getMimeType())
                            .fileType(category.getValue().toUpperCase())
                            .source(asset.getSource().name().toUpperCase())
                            .ocrStatus(asset.getOcrStatus() != null ? asset.getOcrStatus().name().toLowerCase() : "pending")
                            .thumbnailUrl(thumbnailUrl)
                            .createdAt(asset.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());

        return AssetListResponse.builder()
                .assets(assetInfos)
                .totalCount(assetInfos.size())
                .build();
    }
}

