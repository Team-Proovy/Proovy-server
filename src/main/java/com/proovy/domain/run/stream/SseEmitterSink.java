package com.proovy.domain.run.stream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link SseEmitter}에 SSE 블록을 바이트 그대로 쓴다. Spring의 event builder는 {@code id:value}(콜론 뒤 공백 없음)·
 * {@code :comment}로 쓰므로 browser-run-v1 fixture({@code event: …}, {@code : heartbeat})와 같은 바이트를 내려고
 * 직접 만든 블록을 UTF-8 text로 보낸다. Content-Type은 emitter가 {@code text/event-stream}으로 정한다.
 */
final class SseEmitterSink implements RunEventSink {

    private static final MediaType UTF8_TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final SseEmitter emitter;

    SseEmitterSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void write(String block) throws IOException {
        try {
            emitter.send(new RawBlock(block));
        } catch (IllegalStateException e) {
            // 이미 끝난 emitter(단절·timeout과 경합)만 단절로 본다. 그 밖(변환 실패 등)은 stream 오류 경로로 보낸다.
            if (e.getMessage() != null && e.getMessage().contains("already completed")) {
                throw new IOException("emitter already completed", e);
            }
            throw e;
        }
    }

    @Override
    public void fail(Throwable error) {
        try {
            emitter.completeWithError(error);
        } catch (RuntimeException ignored) {
            // 이미 끝난 연결
        }
    }

    @Override
    public void complete() {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // 이미 끝난 연결
        }
    }

    private record RawBlock(String block) implements SseEmitter.SseEventBuilder {

        @Override
        public SseEmitter.SseEventBuilder id(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SseEmitter.SseEventBuilder name(String eventName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SseEmitter.SseEventBuilder reconnectTime(long reconnectTimeMillis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SseEmitter.SseEventBuilder comment(String comment) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SseEmitter.SseEventBuilder data(Object object) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SseEmitter.SseEventBuilder data(Object object, MediaType mediaType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<DataWithMediaType> build() {
            return Set.of(new DataWithMediaType(block, UTF8_TEXT));
        }
    }
}
