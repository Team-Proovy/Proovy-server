package com.proovy.domain.run.entity;

import jakarta.persistence.AttributeConverter;

/**
 * Brain 계약 enum의 wire 철자(소문자 snake_case)를 그대로 DB에 저장하기 위한 공통 계약.
 */
public interface WireValue {

    String wireValue();

    abstract class Converter<E extends Enum<E> & WireValue> implements AttributeConverter<E, String> {

        private final Class<E> type;

        protected Converter(Class<E> type) {
            this.type = type;
        }

        @Override
        public String convertToDatabaseColumn(E attribute) {
            return attribute == null ? null : attribute.wireValue();
        }

        @Override
        public E convertToEntityAttribute(String dbData) {
            if (dbData == null) {
                return null;
            }
            for (E constant : type.getEnumConstants()) {
                if (constant.wireValue().equals(dbData)) {
                    return constant;
                }
            }
            throw new IllegalArgumentException("unknown " + type.getSimpleName() + " value: " + dbData);
        }
    }
}
