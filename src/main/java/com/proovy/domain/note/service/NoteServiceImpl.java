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
import com.proovy.domain.note.entity.Note;
import com.proovy.domain.note.repository.NoteRepository;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
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
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageAssetRepository messageAssetRepository;
    private final MessageToolRepository messageToolRepository;
    private final AssetRepository assetRepository;
    private final com.proovy.domain.user.repository.UserPlanRepository userPlanRepository;
    private final S3Service s3Service;

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
        java.util.Map<Long, Long> conversationCountMap = new java.util.HashMap<>();
        if (!noteIds.isEmpty()) {
            List<java.util.Map<String, Object>> conversationCounts =
                conversationRepository.countByNoteIdIn(noteIds);
            for (java.util.Map<String, Object> row : conversationCounts) {
                Long noteId = ((Number) row.get("noteId")).longValue();
                Long count = ((Number) row.get("count")).longValue();
                conversationCountMap.put(noteId, count);
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
            throw new BusinessException(ErrorCode.NOTE4032);
        }

        // 2. 통계용 정보 수집 (엔티티 조회가 아닌 count/sum 쿼리 사용)
        long conversationCount = conversationRepository.countByNoteId(noteId);

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

        // 6. Conversation ID 목록 조회 (엔티티가 아닌 ID만 조회)
        List<Long> conversationIds = conversationRepository.findIdsByNoteId(noteId);

        // 7. Message ID 목록 조회
        List<Long> messageIds = conversationIds.isEmpty()
                ? List.of()
                : messageRepository.findIdsByConversationIdIn(conversationIds);

        // ========== JPQL 벌크 삭제 시작 (영속성 컨텍스트를 거치지 않음) ==========

        // 8. MessageAsset 삭제 (Asset 기준 + Message 기준 모두)
        if (!assetIds.isEmpty()) {
            messageAssetRepository.deleteByAssetIdInBulk(assetIds);
            log.info("Asset 관련 MessageAsset 삭제 완료");
        }
        if (!messageIds.isEmpty()) {
            messageAssetRepository.deleteByMessageIdInBulk(messageIds);
            log.info("Message 관련 MessageAsset 삭제 완료");
        }

        // 9. MessageTool 삭제
        if (!messageIds.isEmpty()) {
            messageToolRepository.deleteByMessageIdInBulk(messageIds);
            log.info("MessageTool 삭제 완료");
        }

        // 10. Message 삭제
        if (!conversationIds.isEmpty()) {
            messageRepository.deleteByConversationIdInBulk(conversationIds);
            log.info("Message 삭제 완료");
        }

        // 11. Conversation 삭제
        conversationRepository.deleteByNoteIdInBulk(noteId);
        log.info("대화 삭제 완료 - {} 개", conversationCount);

        // 12. Asset 삭제
        assetRepository.deleteByNoteIdInBulk(noteId);
        log.info("자산 삭제 완료 - {} 개", assetCount);

        // 13. Note 삭제 (이제 안전하게 삭제 가능)
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

        // 4. 전체 대화 수 조회
        long totalConversations = conversationRepository.countByNoteId(noteId);

        // 5. 대화 페이징 조회
        Pageable pageable = PageRequest.of(conversationPage, conversationSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Conversation> conversationPage1 = conversationRepository.findByNoteIdOrderByCreatedAtDesc(noteId, pageable);

        // 6. 대화 ID 목록 추출
        List<Long> conversationIds = conversationPage1.getContent().stream()
                .map(Conversation::getId)
                .toList();

        // 7. 메시지 조회 (대화별로 user/assistant 쌍)
        List<Message> messages = conversationIds.isEmpty()
                ? List.of()
                : messageRepository.findByConversationIdInOrderByCreatedAtAsc(conversationIds);

        // 8. 메시지 ID 목록 추출
        List<Long> messageIds = messages.stream()
                .map(Message::getId)
                .toList();

        // 9. 메시지-자산 연결 조회
        List<MessageAsset> messageAssets = messageIds.isEmpty()
                ? List.of()
                : messageAssetRepository.findByMessageIdIn(messageIds);
        Map<Long, List<Asset>> messageAssetMap = messageAssets.stream()
                .collect(Collectors.groupingBy(
                        ma -> ma.getMessage().getId(),
                        Collectors.mapping(MessageAsset::getAsset, Collectors.toList())
                ));

        // 10. 메시지-도구 연결 조회
        List<MessageTool> messageTools = messageIds.isEmpty()
                ? List.of()
                : messageToolRepository.findByMessageIdIn(messageIds);
        Map<Long, List<String>> messageToolMap = messageTools.stream()
                .collect(Collectors.groupingBy(
                        mt -> mt.getMessage().getId(),
                        Collectors.mapping(MessageTool::getToolCode, Collectors.toList())
                ));

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
                            .ocrStatus(asset.getOcrStatus() != null ? asset.getOcrStatus().name().toUpperCase() : "PENDING")
                            .thumbnailUrl(thumbnailUrl)
                            .createdAt(asset.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());

        // 13. 대화별로 메시지 그룹화
        Map<Long, List<Message>> conversationMessageMap = messages.stream()
                .collect(Collectors.groupingBy(msg -> msg.getConversation().getId()));

        // 14. 대화 정보 DTO 생성
        List<NoteDetailResponse.ConversationInfo> conversationInfos = conversationPage1.getContent().stream()
                .map(conversation -> {
                    List<Message> conversationMessages = conversationMessageMap.getOrDefault(conversation.getId(), List.of());

                    Message userMessage = conversationMessages.stream()
                            .filter(msg -> msg.getRole() == MessageRole.USER)
                            .findFirst()
                            .orElse(null);

                    Message assistantMessage = conversationMessages.stream()
                            .filter(msg -> msg.getRole() == MessageRole.ASSISTANT)
                            .findFirst()
                            .orElse(null);

                    return NoteDetailResponse.ConversationInfo.builder()
                            .conversationId(conversation.getId())
                            .userMessage(userMessage != null ? buildMessageInfo(userMessage, messageAssetMap, messageToolMap, true) : null)
                            .assistantMessage(assistantMessage != null ? buildMessageInfo(assistantMessage, messageAssetMap, messageToolMap, false) : null)
                            .createdAt(conversation.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());

        // 15. 사용량 정보 생성
        int conversationUsagePercent = conversationLimit > 0
                ? (int) Math.round((double) totalConversations / conversationLimit * 100)
                : 0;

        NoteDetailResponse.UsageInfo usageInfo = NoteDetailResponse.UsageInfo.builder()
                .conversationCount((int) totalConversations)
                .conversationLimit(conversationLimit)
                .conversationUsagePercent(conversationUsagePercent)
                .build();

        // 16. 페이지 정보 생성
        NoteDetailResponse.PageInfo pageInfo = NoteDetailResponse.PageInfo.builder()
                .page(conversationPage1.getNumber())
                .size(conversationPage1.getSize())
                .totalElements(conversationPage1.getTotalElements())
                .totalPages(conversationPage1.getTotalPages())
                .hasNext(conversationPage1.hasNext())
                .build();

        // 17. lastUsedAt 계산 (updatedAt 또는 createdAt 사용)
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

    private NoteDetailResponse.MessageInfo buildMessageInfo(
            Message message,
            Map<Long, List<Asset>> messageAssetMap,
            Map<Long, List<String>> messageToolMap,
            boolean isUserMessage) {

        List<Asset> assets = messageAssetMap.getOrDefault(message.getId(), List.of());
        List<String> tools = messageToolMap.getOrDefault(message.getId(), List.of());

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

        // AI가 생성한 파일 정보 (현재는 없지만 구조 준비)
        List<NoteDetailResponse.GeneratedFile> generatedFiles = null;
        if (!isUserMessage) {
            // TODO: AI가 생성한 파일 조회 로직 추가
            // 현재는 asset source가 ai_generated인 것들을 찾아야 함
            generatedFiles = assets.stream()
                    .filter(asset -> asset.getSource() == Asset.AssetSource.ai_generated)
                    .map(asset -> NoteDetailResponse.GeneratedFile.builder()
                            .fileId(asset.getId())
                            .fileName(asset.getFileName())
                            .fileType("SOLUTION") // TODO: 실제 타입 매핑 필요
                            .downloadUrl(s3Service.getFileUrl(asset.getS3Key()))
                            .build())
                    .collect(Collectors.toList());
            if (generatedFiles.isEmpty()) {
                generatedFiles = null;
            }
        }

        return NoteDetailResponse.MessageInfo.builder()
                .messageId(message.getId())
                .content(message.getContent())
                .mentionedAssets(mentionedAssets)
                .mentionedTools(isUserMessage && !tools.isEmpty() ? tools : null)
                .usedTools(!isUserMessage && !tools.isEmpty() ? tools : null)
                .generatedFiles(generatedFiles)
                .createdAt(message.getCreatedAt())
                .build();
    }
}

