package com.proovy.domain.note.service;

import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.entity.FileCategory;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.conversation.entity.ChatMessage;
import com.proovy.domain.conversation.entity.MessageRole;
import com.proovy.domain.conversation.repository.ChatMessageRepository;
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

        // 7. 배치 쿼리로 대화 개수 조회 (N+1 문제 해결)
        // ChatMessage 기반으로 대화 쌍(USER+ASSISTANT) 개수 계산
        java.util.Map<Long, Long> conversationCountMap = new java.util.HashMap<>();
        if (!noteIds.isEmpty()) {
            List<Object[]> messageCounts = chatMessageRepository.countByNoteIdIn(noteIds);
            for (Object[] row : messageCounts) {
                Long noteId = ((Number) row[0]).longValue();
                Long count = ((Number) row[1]).longValue();
                // USER+ASSISTANT 쌍이므로 2로 나눔
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

        // 2. 통계용 정보 수집
        long messageCount = chatMessageRepository.countByNoteId(noteId);
        long conversationCount = messageCount / 2; // USER+ASSISTANT 쌍

        // 3. S3 삭제를 위한 Asset 정보만 조회
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

        // 4. S3 파일 삭제
        if (!s3KeysToDelete.isEmpty()) {
            s3Service.deleteFiles(s3KeysToDelete);
            log.info("S3 파일 삭제 완료 - {} 개 파일", s3KeysToDelete.size());
        }

        // 5. ChatMessage 삭제 (벌크)
        chatMessageRepository.deleteByNoteIdInBulk(noteId);
        log.info("대화 메시지 삭제 완료 - {} 개", messageCount);

        // 6. Asset 삭제 (벌크)
        assetRepository.deleteByNoteIdInBulk(noteId);
        log.info("자산 삭제 완료 - {} 개", assetCount);

        // 7. Note 삭제
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
        // TODO: PlanType에 conversationLimit 필드 추가 필요. 현재는 고정값 사용
        int conversationLimit = 50; // 기본 대화 제한 수

        // 4. 전체 메시지 조회 (ChatMessage 사용)
        List<com.proovy.domain.conversation.entity.ChatMessage> allMessages =
                chatMessageRepository.findByNoteIdOrderByCreatedAtAsc(noteId);

        // 5. 대화 쌍 생성 (USER + ASSISTANT를 하나의 대화로 그룹화)
        List<NoteDetailResponse.ConversationInfo> allConversations = new ArrayList<>();
        com.proovy.domain.conversation.entity.ChatMessage userMsg = null;

        for (com.proovy.domain.conversation.entity.ChatMessage msg : allMessages) {
            if (msg.getRole() == MessageRole.USER) {
                userMsg = msg;
            } else if (msg.getRole() == MessageRole.ASSISTANT && userMsg != null) {
                // USER + ASSISTANT 쌍을 하나의 대화로 생성
                NoteDetailResponse.ConversationInfo conversation = NoteDetailResponse.ConversationInfo.builder()
                        .conversationId(msg.getId()) // ChatMessage ID를 대화 ID로 사용
                        .userMessage(buildChatMessageInfo(userMsg, true))
                        .assistantMessage(buildChatMessageInfo(msg, false))
                        .createdAt(userMsg.getCreatedAt())
                        .build();
                allConversations.add(conversation);
                userMsg = null;
            }
        }

        // 6. 페이징 처리 (메모리에서)
        int start = conversationPage * conversationSize;
        int end = Math.min(start + conversationSize, allConversations.size());
        List<NoteDetailResponse.ConversationInfo> pagedConversations =
                start < allConversations.size() ? allConversations.subList(start, end) : List.of();

        // 7. 노트의 모든 자산 조회
        List<Asset> noteAssets = assetRepository.findAllByNoteId(noteId);

        // 8. 자산 정보 DTO 생성
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

        // 9. 사용량 정보 생성
        int totalConversations = allConversations.size();
        int conversationUsagePercent = conversationLimit > 0
                ? (int) Math.round((double) totalConversations / conversationLimit * 100)
                : 0;

        NoteDetailResponse.UsageInfo usageInfo = NoteDetailResponse.UsageInfo.builder()
                .conversationCount(totalConversations)
                .conversationLimit(conversationLimit)
                .conversationUsagePercent(conversationUsagePercent)
                .build();

        // 10. 페이지 정보 생성
        int totalPages = (int) Math.ceil((double) totalConversations / conversationSize);
        NoteDetailResponse.PageInfo pageInfo = NoteDetailResponse.PageInfo.builder()
                .page(conversationPage)
                .size(conversationSize)
                .totalElements((long) totalConversations)
                .totalPages(totalPages)
                .hasNext(conversationPage < totalPages - 1)
                .build();

        // 11. lastUsedAt 계산 (updatedAt 또는 createdAt 사용)
        LocalDateTime lastUsedAt = note.getUpdatedAt() != null ? note.getUpdatedAt() : note.getCreatedAt();

        return NoteDetailResponse.builder()
                .noteId(note.getId())
                .title(note.getTitle())
                .usage(usageInfo)
                .assets(assetInfos)
                .conversations(pagedConversations)
                .conversationPageInfo(pageInfo)
                .createdAt(note.getCreatedAt())
                .lastUsedAt(lastUsedAt)
                .build();
    }

    /**
     * ChatMessage를 MessageInfo DTO로 변환
     */
    private NoteDetailResponse.MessageInfo buildChatMessageInfo(
            com.proovy.domain.conversation.entity.ChatMessage message,
            boolean isUserMessage) {

        // JSONB content에서 text 추출
        String content = "";
        if (message.getContent() != null && message.getContent().has("text")) {
            content = message.getContent().get("text").asText();
        }

        // TODO: mentionedAssets, usedTools 정보는 별도 테이블에서 조회 필요
        // 현재는 기본값 반환

        return NoteDetailResponse.MessageInfo.builder()
                .messageId(message.getId())
                .content(content)
                .mentionedAssets(List.of())
                .usedTools(List.of())
                .createdAt(message.getCreatedAt())
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

