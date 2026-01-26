package com.proovy.domain.note.service;

import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.conversation.entity.*;
import com.proovy.domain.conversation.repository.*;
import com.proovy.domain.note.dto.request.CreateNoteRequest;
import com.proovy.domain.note.dto.response.CreateNoteResponse;
import com.proovy.domain.note.entity.Note;
import com.proovy.domain.note.repository.NoteRepository;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    // 허용된 도구 코드 목록 (실제로는 별도 관리 필요)
    private static final Set<String> ALLOWED_TOOL_CODES = Set.of("SOLUTION", "SUMMARY", "QUIZ", "TRANSLATOR");

    @Override
    public CreateNoteResponse createNote(Long userId, CreateNoteRequest request) {
        log.info("노트 생성 요청 - userId: {}, firstMessage length: {}", userId, request.firstMessage().length());

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

        // 3. mentionedAssetIds 검증
        List<Asset> mentionedAssets = new ArrayList<>();
        if (request.mentionedAssetIds() != null && !request.mentionedAssetIds().isEmpty()) {
            List<Long> uniqueAssetIds = request.mentionedAssetIds().stream()
                    .distinct()
                    .toList();
            mentionedAssets = assetRepository.findAllByIdInAndUserId(uniqueAssetIds, userId);
            if (mentionedAssets.size() != uniqueAssetIds.size()) {
                throw new BusinessException(ErrorCode.ASSET4041);
            }
        }

        // 4. mentionedToolCodes 검증
        if (request.mentionedToolCodes() != null) {
            for (String toolCode : request.mentionedToolCodes()) {
                if (!ALLOWED_TOOL_CODES.contains(toolCode)) {
                    throw new BusinessException(ErrorCode.TOOL4001);
                }
            }
        }

        // 5. 노트 생성 (제목은 우선 간단하게 생성)
        String simpleTitle = generateSimpleTitle(request.firstMessage());
        Note note = Note.builder()
                .user(user)
                .title(simpleTitle)
                .contentMd("")
                .build();
        note = noteRepository.save(note);

        // 6. Conversation 생성
        Conversation conversation = Conversation.builder()
                .note(note)
                .build();
        conversation = conversationRepository.save(conversation);

        // 7. User Message 생성
        Message userMessage = Message.builder()
                .conversation(conversation)
                .role(MessageRole.USER)
                .content(request.firstMessage())
                .status(MessageStatus.COMPLETED)
                .build();
        userMessage = messageRepository.save(userMessage);

        // 8. User Message의 Asset 연결
        if (!mentionedAssets.isEmpty()) {
            for (Asset asset : mentionedAssets) {
                MessageAsset messageAsset = MessageAsset.builder()
                        .message(userMessage)
                        .asset(asset)
                        .build();
                messageAssetRepository.save(messageAsset);
            }
        }

        // 9. User Message의 Tool 연결
        if (request.mentionedToolCodes() != null && !request.mentionedToolCodes().isEmpty()) {
            for (String toolCode : request.mentionedToolCodes()) {
                MessageTool messageTool = MessageTool.builder()
                        .message(userMessage)
                        .toolCode(toolCode)
                        .build();
                messageToolRepository.save(messageTool);
            }
        }

        // 10. Assistant Message 생성 (임시 응답)
        String assistantContent = "집합론 문제를 분석하고 해설지를 생성하겠습니다...";
        Message assistantMessage = Message.builder()
                .conversation(conversation)
                .role(MessageRole.ASSISTANT)
                .content(assistantContent)
                .status(MessageStatus.STREAMING)
                .build();
        assistantMessage = messageRepository.save(assistantMessage);

        // 11. Assistant Message의 Tool 연결 (사용된 도구)
        if (request.mentionedToolCodes() != null && !request.mentionedToolCodes().isEmpty()) {
            for (String toolCode : request.mentionedToolCodes()) {
                MessageTool messageTool = MessageTool.builder()
                        .message(assistantMessage)
                        .toolCode(toolCode)
                        .build();
                messageToolRepository.save(messageTool);
            }
        }

        log.info("노트 생성 완료 - noteId: {}", note.getId());

        // 12. Response 생성
        return buildCreateNoteResponse(
                note,
                conversation,
                userMessage,
                assistantMessage,
                mentionedAssets,
                request.mentionedToolCodes()
        );
    }

    /**
     * 간단한 제목 생성 (AI 사용 전까지 임시)
     */
    private String generateSimpleTitle(String firstMessage) {
        // 첫 메시지에서 최대 50자까지 제목으로 사용
        String title = firstMessage.replaceAll("\\s+", " ").trim();
        if (title.length() > 50) {
            title = title.substring(0, 50);
        }
        return title;
    }

    /**
     * CreateNoteResponse 빌드
     */
    private CreateNoteResponse buildCreateNoteResponse(
            Note note,
            Conversation conversation,
            Message userMessage,
            Message assistantMessage,
            List<Asset> mentionedAssets,
            List<String> mentionedToolCodes
    ) {
        // MentionedAssets DTO 변환
        List<CreateNoteResponse.MentionedAssetDto> mentionedAssetDtos = mentionedAssets.stream()
                .map(asset -> new CreateNoteResponse.MentionedAssetDto(
                        asset.getId(),
                        asset.getFileName()
                ))
                .collect(Collectors.toList());

        // UserMessage DTO
        CreateNoteResponse.UserMessageDto userMessageDto = new CreateNoteResponse.UserMessageDto(
                userMessage.getId(),
                userMessage.getContent(),
                mentionedAssetDtos,
                mentionedToolCodes != null ? mentionedToolCodes : List.of(),
                userMessage.getCreatedAt()
        );

        // AssistantMessage DTO
        CreateNoteResponse.AssistantMessageDto assistantMessageDto = new CreateNoteResponse.AssistantMessageDto(
                assistantMessage.getId(),
                assistantMessage.getContent(),
                mentionedToolCodes != null ? mentionedToolCodes : List.of(),
                assistantMessage.getStatus().name(),
                assistantMessage.getCreatedAt()
        );

        // FirstConversation DTO
        CreateNoteResponse.FirstConversationDto firstConversationDto = new CreateNoteResponse.FirstConversationDto(
                conversation.getId(),
                userMessageDto,
                assistantMessageDto
        );

        // CreateNoteResponse
        return new CreateNoteResponse(
                note.getId(),
                note.getTitle(),
                "USER", // 현재는 AI 사용하지 않으므로 USER로 표시
                50, // 기본 대화 제한
                firstConversationDto,
                note.getCreatedAt()
        );
    }
}

