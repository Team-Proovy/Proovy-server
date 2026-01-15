package com.proovy.domain.auth.entity;

import com.proovy.domain.user.entity.AuthProvider;
import com.proovy.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDateTime;

@Entity
@Table(name = "auth_accounts", uniqueConstraints = {@UniqueConstraint(name = "uk_auth_accounts_provider_provider_user_id", columnNames = {"provider", "provider_user_id"})})
public class AuthAccount {

    @Getter
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "auth_account_id")
    private Long authAccountId;

    @Getter
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 10, nullable = false)
    private AuthProvider provider;

    @Getter
    @Column(name = "provider_user_id", length = 255, nullable = false)
    private String providerUserId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_users_to_auth_accounts_1"))
    private User user;

    protected AuthAccount() {
    }

    public AuthAccount(User user, AuthProvider provider, String providerUserId) {
        this.user = user;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.createdAt = LocalDateTime.now();
    }

}
