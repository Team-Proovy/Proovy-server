package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.opaqueId;
import static com.proovy.domain.run.brain.InternalRunContract.versionLabel;

/** run 생성 시 고정한 corpus 범위. note_id는 요청 Note와 같아야 한다. */
public record RetrievalScope(
        String noteId,
        String corpusVersion,
        String searchExtractionVersion,
        String chunkingVersion,
        String embeddingVersion) {

    public RetrievalScope {
        noteId = opaqueId(noteId, "retrieval_scope.note_id");
        corpusVersion = opaqueId(corpusVersion, "retrieval_scope.corpus_version");
        searchExtractionVersion = versionLabel(searchExtractionVersion, "retrieval_scope.search_extraction_version");
        chunkingVersion = versionLabel(chunkingVersion, "retrieval_scope.chunking_version");
        embeddingVersion = versionLabel(embeddingVersion, "retrieval_scope.embedding_version");
    }
}
