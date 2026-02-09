package com.proovy.domain.note.controller;

import com.proovy.domain.embedding.service.NoteEmbeddingService;
import com.proovy.domain.note.entity.Note;
import com.proovy.domain.note.repository.NoteRepository;
import com.proovy.global.util.HashUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/internal/notes")
@RequiredArgsConstructor
@Slf4j
public class InternalNoteController {

    private static final String DEFAULT_MODEL = "text-embedding-3-small";

    private final NoteRepository noteRepository;
    private final NoteEmbeddingService embeddingService;

    @Value("${internal.api.token}")
    private String internalToken;

    private void validateInternalToken(String token) {
        if (!internalToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid internal token");
        }
    }

    @GetMapping("/{noteId}/embedding-source")
    public ResponseEntity<EmbeddingSourceResponse> getEmbeddingSource(
            @PathVariable Long noteId,
            @RequestHeader("X-Internal-Token") String token
    ) {
        validateInternalToken(token);

        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found"));

        String content = note.getContentMd() == null ? "" : note.getContentMd();
        String contentHash = HashUtils.sha256(content);

        return ResponseEntity.ok(EmbeddingSourceResponse.builder()
                .noteId(note.getId())
                .userId(note.getUser().getId())
                .content(content)
                .contentHash(contentHash)
                .model(DEFAULT_MODEL)
                .build());
    }

    @PostMapping("/{noteId}/embedding")
    public ResponseEntity<Void> upsertEmbedding(
            @PathVariable Long noteId,
            @RequestHeader("X-Internal-Token") String token,
            @RequestBody @Valid UpsertEmbeddingRequest request
    ) {
        validateInternalToken(token);

        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found"));

        String currentContent = note.getContentMd() == null ? "" : note.getContentMd();
        String currentHash = HashUtils.sha256(currentContent);
        if (!currentHash.equals(request.getContentHash())) {
            log.warn("노트 내용 변경 감지. 임베딩 저장 취소: noteId={}", noteId);
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        embeddingService.upsertEmbedding(
                noteId,
                note.getUser().getId(),
                request.getContentHash(),
                request.getModel(),
                request.getVector()
        );

        return ResponseEntity.ok().build();
    }

    @Data
    @Builder
    public static class EmbeddingSourceResponse {
        private Long noteId;
        private Long userId;
        private String content;
        private String contentHash;
        private String model;
    }

    @Data
    public static class UpsertEmbeddingRequest {
        @NotBlank
        private String model;

        @NotBlank
        private String contentHash;

        @NotEmpty
        private List<Double> vector;
    }
}
