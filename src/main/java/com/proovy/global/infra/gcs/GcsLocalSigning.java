package com.proovy.global.infra.gcs;

import com.google.auth.ServiceAccountSigner;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.web.util.UriUtils;

/**
 * 로컬 GCS 에뮬레이터(opt-in) 전용 URL 서명·공개 URL 조립 컨텍스트.
 * 비활성화 시({@link #disabled()}) 운영 Signed URL / URL 조립 경로를 변경하지 않는다.
 */
public final class GcsLocalSigning {

    private static final GcsLocalSigning DISABLED = new GcsLocalSigning(false, null, null);

    private final boolean enabled;
    private final String publicBaseUrl;
    private final ServiceAccountSigner signer;

    private GcsLocalSigning(boolean enabled, String publicBaseUrl, ServiceAccountSigner signer) {
        this.enabled = enabled;
        this.publicBaseUrl = publicBaseUrl;
        this.signer = signer;
    }

    public static GcsLocalSigning disabled() {
        return DISABLED;
    }

    public static GcsLocalSigning enabled(String publicBaseUrl, ServiceAccountSigner signer) {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalArgumentException("publicBaseUrl must not be blank");
        }
        return new GcsLocalSigning(true, stripTrailingSlash(publicBaseUrl), Objects.requireNonNull(signer, "signer"));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String publicBaseUrl() {
        return publicBaseUrl;
    }

    public ServiceAccountSigner signer() {
        return signer;
    }

    /**
     * 로컬 proxy가 그대로 전달할 path-style 공개 URL을 생성한다.
     * 객체 키의 '/' 구분자를 '%2F'로 인코딩하지 않고 segment별로 인코딩해
     * {@code https://<public-host>/<bucket>/<object-key>} 형태를 유지한다.
     */
    public String publicFileUrl(String bucketName, String gcsKey) {
        if (!enabled) {
            throw new IllegalStateException("GcsLocalSigning is not enabled");
        }
        String normalizedKey = stripLeadingSlashes(Objects.requireNonNull(gcsKey, "gcsKey"));
        String[] segments = normalizedKey.split("/", -1);
        StringBuilder encoded = new StringBuilder(normalizedKey.length() + 16);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                encoded.append('/');
            }
            encoded.append(UriUtils.encodePathSegment(segments[i], StandardCharsets.UTF_8));
        }
        return publicBaseUrl + "/" + bucketName + "/" + encoded;
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String stripLeadingSlashes(String value) {
        int start = 0;
        while (start < value.length() && value.charAt(start) == '/') {
            start++;
        }
        return value.substring(start);
    }
}
