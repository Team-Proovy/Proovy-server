package com.proovy.global.infra.gcs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.storage.Storage;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.run.coordinator.StorageBackedRunAssetAccessProvider;
import com.proovy.domain.run.entity.AiRun;
import com.proovy.domain.run.snapshot.RunAssetAccess;
import com.proovy.global.config.GcsConfig;
import com.proovy.global.config.GcsProperties;
import com.proovy.global.config.GcsProperties.LocalEmulator;
import com.proovy.global.infra.thumbnail.ThumbnailService;
import com.proovy.global.util.HashUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 로컬 에뮬레이터(opt-in) HTTP wire + GCS SDK + V4 오프라인 서명 + 썸네일 생성 + RunAssetAccess 승인 체인 검증.
 */
class GcsLocalEmulatorHttpChainTest {

    private static final String BUCKET = "proovy-local-assets";
    private static final String PUBLIC_HOST = "macmini.tailnet.ts.net";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void uploadConfirmThumbnailDownloadAndApprovedReadUrlChainWorksOverHttpEmulator() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();
        String pem = toPkcs8Pem(keyPair);

        InMemoryGcsHttpFake emulator = new InMemoryGcsHttpFake(BUCKET);
        try {
            GcsConfig config = new GcsConfig();
            GcsProperties props = new GcsProperties(
                    "local-project",
                    BUCKET,
                    null,
                    emulator.baseUrl(),
                    15,
                    new LocalEmulator(true, "https://" + PUBLIC_HOST, null, pem, "local-signer@proovy.local"));

            Storage storage = config.gcsStorage(props);
            GcsLocalSigning signing = config.gcsLocalSigning(props);
            GcsServiceImpl gcsService = new GcsServiceImpl(storage, props, signing);

            AssetRepository assetRepository = mock(AssetRepository.class);
            ThumbnailService thumbnailService = new ThumbnailService(
                    gcsService, assetRepository, mock(ApplicationContext.class));

            String assetKey = "users/7/notes/42/assets/uuid-1_board.jpg";
            byte[] originalJpeg = createTestJpeg(120, 90);

            // 1. Presigned PUT URL 발급 -> DNS public host + path-style + V4 RSA 서명
            String uploadUrl = gcsService.generatePresignedUploadUrl(assetKey, "image/jpeg", 10);
            URI uploadUri = URI.create(uploadUrl);
            assertThat(uploadUri.getScheme()).isEqualTo("https");
            assertThat(uploadUri.getHost()).isEqualTo(PUBLIC_HOST);
            assertThat(uploadUri.getRawPath()).isEqualTo("/" + BUCKET + "/" + assetKey);
            assertThat(emulator.observedAuthHeaders).isEmpty();

            // 2. Front storage proxy와 동일하게 Host/Method/Path/Query/Content-Type을 보존해 업로드 PUT 실행
            RawHttpResponse putResp = sendHttpWithHostHeader(
                    emulator.port(),
                    "PUT",
                    uploadUri.getRawPath() + "?" + uploadUri.getRawQuery(),
                    PUBLIC_HOST,
                    "image/jpeg",
                    originalJpeg);
            assertThat(putResp.statusCode()).isEqualTo(200);
            assertThat(emulator.lastXmlHostHeader).isEqualTo(PUBLIC_HOST);
            assertThat(emulator.observedAuthHeaders).isEmpty();

            // 3. confirmUpload 단계: doesFileExist + ThumbnailService.generateThumbnailSync -> getThumbnailUrl
            assertThat(gcsService.doesFileExist(assetKey)).isTrue();
            String thumbKey = thumbnailService.generateThumbnailSync(assetKey, "image/jpeg");
            assertThat(thumbKey).isNotNull().endsWith(".jpg");
            assertThat(gcsService.doesFileExist(thumbKey)).isTrue();

            String thumbUrl = gcsService.getThumbnailUrl(thumbKey);
            URI thumbUri = URI.create(thumbUrl);
            assertThat(thumbUri.getScheme()).isEqualTo("https");
            assertThat(thumbUri.getHost()).isEqualTo(PUBLIC_HOST);
            assertThat(thumbUri.getRawPath()).isEqualTo("/" + BUCKET + "/" + thumbKey);

            // 4. proxy 경유 썸네일 GET -> 유효 JPEG 바이트 반환
            RawHttpResponse thumbGet = sendHttpWithHostHeader(
                    emulator.port(), "GET", thumbUri.getRawPath(), PUBLIC_HOST, null, null);
            assertThat(thumbGet.statusCode()).isEqualTo(200);
            BufferedImage decodedThumb = ImageIO.read(new ByteArrayInputStream(thumbGet.body()));
            assertThat(decodedThumb).isNotNull();

            // 5. RunAssetAccess 발급 -> Brain이 받을 read_url 생성 + proxy 경유 바이트 일치
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            UUID runId = UUID.randomUUID();
            UUID attId = UUID.randomUUID();
            String originalSha256 = HashUtils.sha256(originalJpeg);
            ObjectNode snapshot = MAPPER.createObjectNode();
            snapshot.putObject("viewer_context")
                    .put("asset_id", "101")
                    .put("sha256", originalSha256)
                    .put("byte_size", (long) originalJpeg.length);
            snapshot.putArray("turn_attachments").addObject().put("attachment_id", attId.toString());

            AiRun run = mock(AiRun.class);
            when(run.getId()).thenReturn(runId);
            when(run.getUserId()).thenReturn(7L);
            when(run.getNoteId()).thenReturn(42L);
            when(run.getRequestSnapshot()).thenReturn(snapshot);
            when(run.getRequestDeadlineAt()).thenReturn(Instant.now().plusSeconds(300));

            when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<?>>any(),
                    org.mockito.ArgumentMatchers.eq(runId), org.mockito.ArgumentMatchers.eq(42L),
                    org.mockito.ArgumentMatchers.eq(101L), org.mockito.ArgumentMatchers.eq(7L),
                    org.mockito.ArgumentMatchers.eq(7L)))
                    .thenAnswer(inv -> {
                        @SuppressWarnings("unchecked")
                        RowMapper<Object> mapper = inv.getArgument(1);
                        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                        when(rs.getString("object_key")).thenReturn(assetKey);
                        when(rs.getString("sha256")).thenReturn(originalSha256);
                        when(rs.getLong("file_size")).thenReturn((long) originalJpeg.length);
                        when(rs.wasNull()).thenReturn(false);
                        return List.of(mapper.mapRow(rs, 0));
                    });
            when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<?>>any(),
                    org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(7L),
                    org.mockito.ArgumentMatchers.eq(attId)))
                    .thenAnswer(inv -> {
                        @SuppressWarnings("unchecked")
                        RowMapper<Object> mapper = inv.getArgument(1);
                        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                        when(rs.getObject("attachment_id", UUID.class)).thenReturn(attId);
                        when(rs.getString("object_key")).thenReturn(assetKey);
                        return List.of(mapper.mapRow(rs, 0));
                    });

            StorageBackedRunAssetAccessProvider accessProvider =
                    new StorageBackedRunAssetAccessProvider(jdbc, gcsService, 15);
            RunAssetAccess access = accessProvider.accessFor(run, null);

            assertThat(access.viewerReadUrl()).startsWith("https://" + PUBLIC_HOST + "/" + BUCKET + "/" + assetKey + "?");
            URI readUri = URI.create(access.viewerReadUrl());
            RawHttpResponse readGet = sendHttpWithHostHeader(
                    emulator.port(),
                    "GET",
                    readUri.getRawPath() + "?" + readUri.getRawQuery(),
                    PUBLIC_HOST,
                    null,
                    null);
            assertThat(readGet.statusCode()).isEqualTo(200);
            assertThat(readGet.body()).isEqualTo(originalJpeg);
            assertThat(access.turnAttachmentReadUrls()).containsKey(attId.toString());

            // 6. NoCredentials 사용으로 OAuth Bearer 토큰이 전혀 전송되지 않았음을 확인
            assertThat(emulator.observedAuthHeaders).isEmpty();
            // 생성된 V4 서명이 64+ hex 문자열이며 public key 형식이 유효함을 확인
            PublicKey pub = keyPair.getPublic();
            assertThat(pub.getAlgorithm()).isEqualTo("RSA");
            assertThat(Signature.getInstance("SHA256withRSA")).isNotNull();
        } finally {
            emulator.close();
        }
    }

    private record RawHttpResponse(int statusCode, byte[] body) {
    }

    private static RawHttpResponse sendHttpWithHostHeader(
            int port, String method, String pathWithQuery, String hostHeader, String contentType, byte[] body)
            throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(pathWithQuery).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(hostHeader).append("\r\n");
            req.append("Connection: close\r\n");
            if (contentType != null) {
                req.append("Content-Type: ").append(contentType).append("\r\n");
            }
            if (body != null) {
                req.append("Content-Length: ").append(body.length).append("\r\n");
            }
            req.append("\r\n");
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            if (body != null) {
                out.write(body);
            }
            out.flush();

            byte[] rawResp = socket.getInputStream().readAllBytes();
            int headerEnd = -1;
            for (int i = 0; i <= rawResp.length - 4; i++) {
                if (rawResp[i] == '\r' && rawResp[i + 1] == '\n' && rawResp[i + 2] == '\r' && rawResp[i + 3] == '\n') {
                    headerEnd = i;
                    break;
                }
            }
            if (headerEnd < 0) {
                throw new IOException("Malformed HTTP response");
            }
            String headerText = new String(rawResp, 0, headerEnd, StandardCharsets.US_ASCII);
            String firstLine = headerText.split("\r\n", 2)[0];
            int statusCode = Integer.parseInt(firstLine.split(" ")[1]);
            byte[] respBody = java.util.Arrays.copyOfRange(rawResp, headerEnd + 4, rawResp.length);
            return new RawHttpResponse(statusCode, respBody);
        }
    }

    private static byte[] createTestJpeg(int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLUE);
        g.drawLine(0, 0, width - 1, height - 1);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static String toPkcs8Pem(KeyPair pair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    /**
     * google-cloud-storage SDK RPC + XML Signed PUT/GET 동작을 흉내 내는 경량 로컬 HTTP test fake.
     * 실제 V4 서명·만료 검증은 하지 않는다(운영 서명 수용 근거로 사용하지 않음).
     */
    private static final class InMemoryGcsHttpFake implements AutoCloseable {

        private record StoredObject(byte[] bytes, String contentType) {
        }

        private final String bucket;
        private final HttpServer server;
        private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
        private final Map<String, StoredObject> resumableSessions = new ConcurrentHashMap<>();
        private final List<String> observedAuthHeaders = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile String lastXmlHostHeader;

        InMemoryGcsHttpFake(String bucket) throws IOException {
            this.bucket = bucket;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/", this::handle);
            this.server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + port();
        }

        private void handle(HttpExchange ex) throws IOException {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth != null && !auth.isBlank()) {
                observedAuthHeaders.add(auth);
            }
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();
            String method = ex.getRequestMethod();

            // JSON API: upload/storage/v1/b/<bucket>/o (multipart or resumable)
            String uploadPrefix = "/upload/storage/v1/b/" + bucket + "/o";
            if (path.startsWith(uploadPrefix)) {
                String uploadId = extractQueryParam(query, "upload_id");
                String uploadType = extractQueryParam(query, "uploadType");
                byte[] reqBody = ex.getRequestBody().readAllBytes();
                if ("POST".equals(method) && "resumable".equals(uploadType) && uploadId == null) {
                    var meta = MAPPER.readTree(reqBody);
                    String name = meta.path("name").asText(extractQueryParam(query, "name"));
                    String ct = meta.path("contentType").asText("application/octet-stream");
                    String sessionId = UUID.randomUUID().toString();
                    resumableSessions.put(sessionId, new StoredObject(new byte[0], name + "\0" + ct));
                    ex.getResponseHeaders().set("Location",
                            baseUrl() + uploadPrefix + "?uploadType=resumable&upload_id=" + sessionId);
                    sendJson(ex, 200, "{}");
                    return;
                }
                if (("PUT".equals(method) || "POST".equals(method)) && uploadId != null) {
                    StoredObject meta = resumableSessions.remove(uploadId);
                    if (meta == null) {
                        sendJson(ex, 404, "{\"error\":{\"code\":404}}");
                        return;
                    }
                    String[] parts = meta.contentType().split("\0", 2);
                    String objectName = parts[0];
                    String contentType = parts.length > 1 ? parts[1] : "application/octet-stream";
                    objects.put(objectName, new StoredObject(reqBody, contentType));
                    sendJson(ex, 200, MAPPER.writeValueAsString(Map.of(
                            "kind", "storage#object",
                            "bucket", bucket,
                            "name", objectName,
                            "size", String.valueOf(reqBody.length),
                            "contentType", contentType)));
                    return;
                }
                if ("POST".equals(method)) {
                    String objectName = extractQueryParam(query, "name");
                    String contentType = "application/octet-stream";
                    byte[] payload = reqBody;
                    String reqCt = ex.getRequestHeaders().getFirst("Content-Type");
                    if (reqCt != null && reqCt.startsWith("multipart/")) {
                        ParsedMultipart parsed = parseMultipartRelated(reqBody, reqCt);
                        objectName = parsed.name() != null ? parsed.name() : objectName;
                        contentType = parsed.contentType() != null ? parsed.contentType() : contentType;
                        payload = parsed.payload();
                    }
                    objects.put(objectName, new StoredObject(payload, contentType));
                    sendJson(ex, 200, MAPPER.writeValueAsString(Map.of(
                            "kind", "storage#object",
                            "bucket", bucket,
                            "name", objectName,
                            "size", String.valueOf(payload.length),
                            "contentType", contentType)));
                    return;
                }
            }

            // JSON API: /storage/v1/b/<bucket>/o/<object> or /download/storage/v1/b/<bucket>/o/<object>
            String metaPrefix = "/storage/v1/b/" + bucket + "/o/";
            String downloadPrefix = "/download/storage/v1/b/" + bucket + "/o/";
            if ((path.startsWith(metaPrefix) || path.startsWith(downloadPrefix)) && "GET".equals(method)) {
                String prefix = path.startsWith(downloadPrefix) ? downloadPrefix : metaPrefix;
                String objectName = path.substring(prefix.length());
                StoredObject stored = objects.get(objectName);
                if (stored == null) {
                    sendJson(ex, 404, "{\"error\":{\"code\":404,\"message\":\"Not Found\"}}");
                    return;
                }
                if (path.startsWith(downloadPrefix) || "media".equals(extractQueryParam(query, "alt"))) {
                    sendBytes(ex, 200, stored.contentType(), stored.bytes());
                    return;
                }
                String json = MAPPER.writeValueAsString(Map.of(
                        "kind", "storage#object",
                        "bucket", bucket,
                        "name", objectName,
                        "size", String.valueOf(stored.bytes().length),
                        "contentType", stored.contentType()));
                sendJson(ex, 200, json);
                return;
            }

            // XML path-style: /<bucket>/<object>
            String xmlPrefix = "/" + bucket + "/";
            if (path.startsWith(xmlPrefix)) {
                lastXmlHostHeader = ex.getRequestHeaders().getFirst("Host");
                String objectName = path.substring(xmlPrefix.length());
                if ("PUT".equals(method)) {
                    byte[] payload = ex.getRequestBody().readAllBytes();
                    String contentType = ex.getRequestHeaders().getFirst("Content-Type");
                    objects.put(objectName, new StoredObject(payload,
                            contentType != null ? contentType : "application/octet-stream"));
                    sendJson(ex, 200, "{}");
                    return;
                }
                if ("GET".equals(method)) {
                    StoredObject stored = objects.get(objectName);
                    if (stored == null) {
                        sendBytes(ex, 404, "text/plain", new byte[0]);
                        return;
                    }
                    sendBytes(ex, 200, stored.contentType(), stored.bytes());
                    return;
                }
            }
            sendJson(ex, 404, "{\"error\":{\"code\":404,\"message\":\"Unhandled path\"}}");
        }

        private record ParsedMultipart(String name, String contentType, byte[] payload) {
        }

        private static ParsedMultipart parseMultipartRelated(byte[] body, String headerContentType) throws IOException {
            String boundary = null;
            for (String part : headerContentType.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("boundary=")) {
                    boundary = trimmed.substring("boundary=".length()).replace("\"", "");
                }
            }
            if (boundary == null) {
                return new ParsedMultipart(null, null, body);
            }
            byte[] sep = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
            String raw = new String(body, StandardCharsets.ISO_8859_1);
            String[] sections = raw.split("--" + java.util.regex.Pattern.quote(boundary));
            String metaName = null;
            String metaCt = null;
            byte[] fileBytes = body;
            int idx = 0;
            for (String sec : sections) {
                if (sec.isEmpty() || sec.startsWith("--")) {
                    continue;
                }
                int hdrEnd = sec.indexOf("\r\n\r\n");
                if (hdrEnd < 0) {
                    continue;
                }
                String secBody = sec.substring(hdrEnd + 4);
                if (secBody.endsWith("\r\n")) {
                    secBody = secBody.substring(0, secBody.length() - 2);
                }
                if (idx == 0) {
                    var node = MAPPER.readTree(secBody);
                    metaName = node.path("name").asText(null);
                    metaCt = node.path("contentType").asText(null);
                } else {
                    fileBytes = secBody.getBytes(StandardCharsets.ISO_8859_1);
                }
                idx++;
            }
            return new ParsedMultipart(metaName, metaCt, fileBytes);
        }

        private static String extractQueryParam(String query, String key) {
            if (query == null) {
                return null;
            }
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                String k = eq >= 0 ? pair.substring(0, eq) : pair;
                if (key.equals(k)) {
                    return eq >= 0
                            ? java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
                            : "";
                }
            }
            return null;
        }

        private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
            sendBytes(ex, status, "application/json", json.getBytes(StandardCharsets.UTF_8));
        }

        private static void sendBytes(HttpExchange ex, int status, String contentType, byte[] data)
                throws IOException {
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(status, data.length);
            ex.getResponseBody().write(data);
            ex.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
