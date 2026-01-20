package com.proovy.domain.user.repository;

import com.proovy.domain.user.entity.OAuthProvider;
import com.proovy.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByPhone(String phone);

    /**
     * OAuth Provider와 Provider 고유 ID로 사용자 조회
     * @param provider OAuth 제공자 (OAuthProvider enum)
     * @param providerUserId 제공자 측 고유 ID
     */
    Optional<User> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);

    /**
     * 특정 Provider로 가입한 사용자 존재 여부 확인
     */
    boolean existsByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);
}
