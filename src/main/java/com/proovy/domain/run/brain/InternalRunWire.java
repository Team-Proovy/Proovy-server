package com.proovy.domain.run.brain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.proovy.domain.run.entity.RunCancelCause;
import java.io.IOException;

/**
 * internal-run-v1·brain-source-v1 snake_case wire 전용 Jackson 설정. 브라우저 camelCase 모델(Spring 기본 mapper)과
 * 섞지 않으려고 이 클래스로만 (역)직렬화한다.
 * <ul>
 *   <li>closed model: 알 수 없는 필드·중복 key·뒤따르는 token을 거부한다(Brain {@code extra="forbid"}).</li>
 *   <li>타입 강제 변환 없음: 문자열↔숫자, 실수→정수, boolean→숫자를 거부한다(Brain {@code StrictInt}).</li>
 *   <li>null 값은 쓰지 않는다(Brain {@code exclude_none}). 시각은 offset 있는 ISO 문자열이고 offset을 바꾸지 않는다.</li>
 *   <li>읽기 실패는 {@link InternalRunContractViolation}이며 message에 입력 값을 넣지 않는다.</li>
 * </ul>
 */
public final class InternalRunWire {

    private static final ObjectMapper MAPPER = build();

    private InternalRunWire() {
    }

    /** entity enum({@link RunCancelCause})을 고치지 않고 wire 철자로 쓰기 위한 mix-in. */
    private interface WireValueMixIn {
        @JsonValue
        String wireValue();
    }

    private static ObjectMapper build() {
        JsonMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .addMixIn(RunCancelCause.class, WireValueMixIn.class)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .build();
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Float)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Enum)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
        return mapper;
    }

    public static byte[] write(Object message) {
        try {
            return MAPPER.writeValueAsBytes(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("internal run message is not serializable: "
                    + message.getClass().getSimpleName());
        }
    }

    public static JsonNode tree(Object message) {
        return MAPPER.valueToTree(message);
    }

    public static <T> T read(byte[] body, Class<T> type) {
        try {
            return MAPPER.readValue(body, type);
        } catch (IOException e) {
            throw sanitized(type, e);
        }
    }

    public static <T> T read(String body, Class<T> type) {
        try {
            return MAPPER.readValue(body, type);
        } catch (IOException e) {
            throw sanitized(type, e);
        }
    }

    public static <T> T read(JsonNode body, Class<T> type) {
        try {
            return MAPPER.treeToValue(body, type);
        } catch (IOException | IllegalArgumentException e) {
            throw sanitized(type, e);
        }
    }

    /** 원문 JSON을 tree로만 읽는다(중복 key·뒤따르는 token 거부). */
    public static JsonNode readTree(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new InternalRunContractViolation("malformed JSON");
        }
    }

    /**
     * Jackson 오류를 값 없는 message로 바꾼다. 우리 검증 오류(필드 이름만 담음)는 그대로 쓰고 cause는 붙이지 않는다
     * (Jackson message에는 입력 값이 들어갈 수 있다).
     */
    private static InternalRunContractViolation sanitized(Class<?> type, Exception error) {
        String target = type.getSimpleName();
        Throwable cause = error instanceof ValueInstantiationException ? error.getCause() : error;
        if (cause instanceof InternalRunContractViolation violation) {
            return new InternalRunContractViolation(target + ": " + violation.getMessage());
        }
        String kind;
        if (error instanceof UnrecognizedPropertyException) {
            kind = "unknown field";
        } else if (error instanceof InvalidFormatException) {
            kind = "invalid value";
        } else if (error instanceof MismatchedInputException) {
            kind = "type mismatch or missing field";
        } else if (error instanceof JsonProcessingException processing && processing.getLocation() != null) {
            kind = "malformed JSON";
        } else {
            kind = "invalid value";
        }
        return new InternalRunContractViolation(target + ": " + kind);
    }
}
