package com.proovy.domain.auth.repository;

import com.proovy.domain.auth.entity.AccessTokenBlacklist;
import org.springframework.data.repository.CrudRepository;

public interface AccessTokenBlacklistRepository extends CrudRepository<AccessTokenBlacklist, String> {
    boolean existsByToken(String token);
}
