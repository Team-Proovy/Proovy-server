package com.proovy.support.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 실제 HTTP 연결로 브라우저 SSE를 읽는 테스트 client (1.86 {@code BrowserRunSsePostgresTest}와 같은 판독 규칙).
 * {@link #close()}는 연결을 끊는다(브라우저 단절).
 */
public final class SseClient implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Block END = new Block(null, null, null, null);

    /** SSE 블록 하나. comment 블록은 event·id·data가 null이다. raw는 끝 빈 줄을 뺀 원문이다. */
    public record Block(String event, String id, String data, String raw) {

        public JsonNode json() {
            try {
                return JSON.readTree(data);
            } catch (IOException e) {
                throw new AssertionError("frame data is not JSON: " + data, e);
            }
        }

        public long seq() {
            return Long.parseLong(id.substring(id.indexOf(':') + 1));
        }
    }

    public final int status;
    public final String contentType;
    public final String errorBody;
    private final InputStream body;
    private final Duration wait;
    private final BlockingQueue<Block> blocks = new LinkedBlockingQueue<>();
    /** reader thread에서 난 판독 오류 — 다음 frame 요청에서 test thread로 다시 던진다. */
    private volatile Throwable readerFailure;

    private SseClient(int status, String contentType, String errorBody, InputStream body, Duration wait) {
        this.status = status;
        this.contentType = contentType;
        this.errorBody = errorBody;
        this.body = body;
        this.wait = wait;
    }

    public static SseClient open(HttpClient client, HttpRequest request, Duration wait) throws Exception {
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (response.statusCode() != 200) {
            String error;
            try (InputStream in = response.body()) {
                error = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            SseClient failed = new SseClient(response.statusCode(), contentType, error, null, wait);
            failed.blocks.add(END);
            return failed;
        }
        SseClient sse = new SseClient(200, contentType, null, response.body(), wait);
        Thread reader = new Thread(sse::read, "sse-test-client");
        reader.setDaemon(true);
        reader.start();
        return sse;
    }

    private void read() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    lines.add(line);
                    continue;
                }
                if (!lines.isEmpty()) {
                    blocks.add(parse(lines));
                    lines = new ArrayList<>();
                }
            }
        } catch (IOException closed) {
            // close()로 끊음
        } catch (RuntimeException | AssertionError e) {
            readerFailure = e;
        } finally {
            blocks.add(END);
        }
    }

    private static Block parse(List<String> lines) {
        String event = null;
        String id = null;
        String data = null;
        for (String line : lines) {
            if (line.startsWith("event: ")) {
                event = line.substring(7);
            } else if (line.startsWith("id: ")) {
                id = line.substring(4);
            } else if (line.startsWith("data: ")) {
                // 여러 data 줄은 SSE 규칙대로 줄바꿈으로 잇는다
                data = data == null ? line.substring(6) : data + "\n" + line.substring(6);
            } else if (!line.startsWith(":")) {
                throw new AssertionError("unexpected SSE line: " + line);
            }
        }
        return new Block(event, id, data, String.join("\n", lines));
    }

    /** heartbeat를 건너뛴 다음 frame. 끝났으면 null. */
    public Block nextFrameOrEnd() throws InterruptedException {
        while (true) {
            Block block = blocks.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
            if (block == null) {
                throw new AssertionError("no SSE block in " + wait);
            }
            if (block == END) {
                blocks.add(END);
                if (readerFailure != null) {
                    throw new AssertionError("SSE reader failed", readerFailure);
                }
                return null;
            }
            if (block.event() != null) {
                return block;
            }
        }
    }

    public Block nextFrame() throws InterruptedException {
        Block frame = nextFrameOrEnd();
        if (frame == null) {
            throw new AssertionError("stream ended before the next frame");
        }
        return frame;
    }

    /** 서버가 연결을 닫을 때까지의 frame. */
    public List<Block> framesUntilEnd() throws InterruptedException {
        List<Block> frames = new ArrayList<>();
        Block block;
        while ((block = nextFrameOrEnd()) != null) {
            frames.add(block);
        }
        return frames;
    }

    @Override
    public void close() throws IOException {
        if (body != null) {
            body.close();
        }
    }
}
