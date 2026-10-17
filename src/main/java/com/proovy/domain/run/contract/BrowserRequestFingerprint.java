package com.proovy.domain.run.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * requestId 재사용 판정용 의미 fingerprint ({@value BrowserRunContract#FINGERPRINT_ALGORITHM}).
 * <ul>
 *   <li>requestId 제외, text·latex는 {@link BrowserRunContract#strip} 결과</li>
 *   <li>비순서 배열은 정렬, 빈 배열·null은 부재와 같다</li>
 *   <li>noteId 부재와 존재는 다르다. 기본값(예: requestedOutputs [] → ["text"])을 채우지 않는다</li>
 * </ul>
 * 같은 사용자·같은 fingerprint면 기존 run replay, 아니면 409 RUN4092.
 *
 * @param canonical {@link CanonicalJson} 문서
 * @param sha256    canonical UTF-8 바이트의 sha256 소문자 hex
 */
public record BrowserRequestFingerprint(String canonical, String sha256) {

    public static BrowserRequestFingerprint of(ConversationRunRequest request) {
        String canonical = CanonicalJson.write(document(request));
        return new BrowserRequestFingerprint(canonical, sha256Hex(canonical));
    }

    static Map<String, Object> document(ConversationRunRequest request) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("noteId", request.noteId());
        document.put("text", BrowserRunContract.strip(request.text()));
        if (request.latex() != null) {
            document.put("latex", BrowserRunContract.strip(request.latex()));
        }
        document.put("requestedTasks", sorted(request.requestedTasks(), RequestedTask::wireValue));
        document.put("requestedOutputs", sorted(request.requestedOutputs(), RequestedOutput::wireValue));
        document.put("turnAttachmentIds", sorted(request.turnAttachmentIds(), Function.identity()));
        ViewerFocus focus = request.viewerFocus();
        if (focus != null) {
            Map<String, Object> focusDocument = new LinkedHashMap<>();
            focusDocument.put("noteAssetId", focus.noteAssetId());
            focusDocument.put("page", focus.page());
            PageSelection selection = focus.selection();
            if (selection != null) {
                focusDocument.put("selection", Map.of(
                        "left", selection.left(),
                        "top", selection.top(),
                        "right", selection.right(),
                        "bottom", selection.bottom()));
            }
            document.put("viewerFocus", focusDocument);
        }
        return document;
    }

    private static <T> List<String> sorted(List<T> values, Function<T, String> wire) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().map(wire).sorted().toList();
    }

    private static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
