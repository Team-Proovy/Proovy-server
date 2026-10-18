package com.proovy.domain.run.snapshot;

import com.proovy.domain.run.brain.ExecutionLimits;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * {@code proovy.run.snapshot.*} — 새 run을 접수할 때 snapshot에 고정하는 정책 값.
 * <p>
 * 이 값은 <b>접수 시점에만</b> 읽는다. 저장된 run의 재전달·상태 조회·재시작 복원은 저장된 snapshot만 쓰므로,
 * 설정을 바꿔도 이미 접수한 run의 의미·fingerprint·deadline은 바뀌지 않는다.
 *
 * @param locale           브라우저가 보내지 않는 input locale
 * @param historyMaxTokens history 추정 token 상한(제품 계약 24,000. 더 크게 설정할 수 없다)
 * @param requestTimeout   접수 시각부터 절대 {@code request_deadline_at}까지
 * @param budget           budget policy version과 실행 상한
 * @param retrieval        Note Corpus 검색 version(S10 전까지 고정 label)
 * @param assetVersions    Viewer Focus에 적용하는 upload policy·Page Understanding·image normalization version
 * @param turnAttachments  이번 turn 직접 첨부 한도(초과는 RUN4221)
 */
@ConfigurationProperties(prefix = "proovy.run.snapshot")
public record RunSnapshotProperties(
        @DefaultValue("ko-KR") String locale,
        @DefaultValue("24000") int historyMaxTokens,
        @DefaultValue("3m") Duration requestTimeout,
        @DefaultValue Budget budget,
        @DefaultValue Retrieval retrieval,
        @DefaultValue AssetVersions assetVersions,
        @DefaultValue TurnAttachmentPolicy turnAttachments) {

    /** 제품 계약의 history 상한(docs/plan/22·17). Brain {@code history_max_tokens}도 이 값을 넘을 수 없다. */
    public static final int CONTRACT_HISTORY_MAX_TOKENS = 24_000;
    private static final Pattern VERSION_LABEL = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Duration MAX_REQUEST_TIMEOUT = Duration.ofMinutes(30);

    public RunSnapshotProperties {
        if (historyMaxTokens < 1 || historyMaxTokens > CONTRACT_HISTORY_MAX_TOKENS) {
            throw new IllegalArgumentException("proovy.run.snapshot.history-max-tokens must be within 1..24000");
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                || requestTimeout.compareTo(MAX_REQUEST_TIMEOUT) > 0) {
            throw new IllegalArgumentException("proovy.run.snapshot.request-timeout must be within (0, 30m]");
        }
        label(budget.policyVersion(), "budget.policy-version");
        label(retrieval.searchExtractionVersion(), "retrieval.search-extraction-version");
        label(retrieval.chunkingVersion(), "retrieval.chunking-version");
        label(retrieval.embeddingVersion(), "retrieval.embedding-version");
        label(assetVersions.uploadPolicyVersion(), "asset-versions.upload-policy-version");
        label(assetVersions.pageUnderstandingVersion(), "asset-versions.page-understanding-version");
        label(assetVersions.imageNormalizationVersion(), "asset-versions.image-normalization-version");
        // 생성자 검사(음수 거부)를 기동 시점에 한 번 돌린다.
        budget.limits();
    }

    private static void label(String value, String field) {
        if (value == null || !VERSION_LABEL.matcher(value).matches()) {
            throw new IllegalArgumentException("proovy.run.snapshot." + field + " must be a version label");
        }
    }

    /** 실행 상한. 0은 "그 자원을 쓰지 않음"이고 음수는 기동 실패다. */
    public record Budget(
            @DefaultValue("budget-v1") String policyVersion,
            @DefaultValue("40") long maxLlmCalls,
            @DefaultValue("40") long maxToolCalls,
            @DefaultValue("100000") long maxOutputTokens,
            @DefaultValue("5000000") long maxCostMicros,
            @DefaultValue("5") long maxPageUnderstandings,
            @DefaultValue("10") long maxProblems,
            @DefaultValue("50") long maxClaims) {

        public ExecutionLimits limits() {
            return new ExecutionLimits(maxLlmCalls, maxToolCalls, maxOutputTokens, maxCostMicros,
                    maxPageUnderstandings, maxProblems, maxClaims);
        }
    }

    public record Retrieval(
            @DefaultValue("se-v1") String searchExtractionVersion,
            @DefaultValue("chunk-v1") String chunkingVersion,
            @DefaultValue("emb-v1") String embeddingVersion) {
    }

    public record AssetVersions(
            @DefaultValue("upload-v1") String uploadPolicyVersion,
            @DefaultValue("pu-v1") String pageUnderstandingVersion,
            @DefaultValue("imgnorm-v1") String imageNormalizationVersion) {
    }

    /** 초기 제품 한도(docs/plan/22): 3개·총 30MB, PDF당 20MB·50 pages, 이미지당 15MB·40 megapixels. */
    public record TurnAttachmentPolicy(
            @DefaultValue("3") int maxCount,
            @DefaultValue("30MB") DataSize maxTotalSize,
            @DefaultValue("20MB") DataSize maxPdfSize,
            @DefaultValue("50") int maxPdfPages,
            @DefaultValue("15MB") DataSize maxImageSize,
            @DefaultValue("40000000") long maxImagePixels) {
    }
}
