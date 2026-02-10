package com.proovy.domain.embedding.service;

import com.proovy.domain.embedding.NoteEmbedding;
import com.proovy.domain.embedding.NoteEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class NoteEmbeddingService {

    private static final String DEFAULT_MODEL = "text-embedding-3-small";
    private static final int EMBEDDING_DIMENSION = 1536;

    private final NoteEmbeddingRepository embeddingRepository;

    public void upsertEmbedding(Long noteId, Long userId, String contentHash, String model, List<Double> vectorList) {
        try {
            validateVector(vectorList);
            String resolvedModel = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
            String vectorString = vectorList.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",", "[", "]"));

            Optional<NoteEmbedding> existing = embeddingRepository.findByNoteIdAndEmbeddingModel(noteId, resolvedModel);

            if (existing.isPresent()) {
                NoteEmbedding embedding = existing.get();
                embedding.updateVector(vectorString, contentHash);
                log.info("임베딩 업데이트 완료: noteId={}, model={}", noteId, resolvedModel);
                return;
            }

            NoteEmbedding embedding = NoteEmbedding.builder()
                    .noteId(noteId)
                    .userId(userId)
                    .contentHash(contentHash)
                    .embeddingModel(resolvedModel)
                    .vector(vectorString)
                    .build();
            embeddingRepository.save(embedding);
            log.info("임베딩 신규 저장 완료: noteId={}, model={}", noteId, resolvedModel);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("임베딩 저장 실패: noteId={}", noteId, e);
            throw new RuntimeException("임베딩 저장 실패", e);
        }
    }

    private void validateVector(List<Double> vectorList) {
        if (vectorList == null || vectorList.isEmpty()) {
            throw new IllegalArgumentException("벡터 데이터가 비어 있습니다.");
        }
        if (vectorList.size() != EMBEDDING_DIMENSION) {
            throw new IllegalArgumentException("벡터 차원은 1536이어야 합니다.");
        }
        if (vectorList.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("벡터에 null 값이 포함되어 있습니다.");
        }
    }
}
