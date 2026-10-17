package com.proovy.global.entity;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * 애플리케이션이 ID를 직접 할당하는 엔티티의 공통 부모.
 *
 * <p>할당형 ID에 {@code @Version}이 없으면 Spring Data {@code save()}가 이를 기존 row로 보고
 * {@code merge}(SELECT 후 병합)를 호출한다. 그러면 이미 있는 ID에 대한 저장이 SQL 없이 조용히
 * 성공해 request 멱등 키·ledger PK 같은 DB 유일성 제약이 우회된다. 새로 만든 인스턴스는
 * 항상 {@code persist}(INSERT)로 가게 하여 중복이 {@code DataIntegrityViolationException}으로 드러나게 한다.
 */
@MappedSuperclass
public abstract class AssignedIdEntity<ID> implements Persistable<ID> {

    @Transient
    private boolean newEntity = true;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }
}
