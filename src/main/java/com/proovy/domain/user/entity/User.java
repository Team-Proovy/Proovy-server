package com.proovy.domain.user.entity;

import jakarta.persistence.*;
import lombok.Builder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_user_provider", columnList = "provider, providerUserId")
})
@EntityListeners(AuditingEntityListener.class)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(length = 20)
    private String phone;

    @Column(length = 50)
    private String name;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Column(length = 100)
    private String department;

    @Column(name = "referral_source", length = 20)
    private String referralSource;

    // OAuth
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private OAuthProvider provider;

    @Column(length = 100)
    private String providerUserId;

    @Column(length = 255)
    private String email;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // JPA용 기본 생성자
    protected User() {
    }

    @Builder
    public User(String phone, String name, String nickname, String department,
                String referralSource, OAuthProvider provider, String providerUserId, String email) {
        this.phone = phone;
        this.name = name;
        this.nickname = nickname;
        this.department = department;
        this.referralSource = referralSource;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.email = email;
    }

    // Getter
    public Long getId() { return id; }
    public String getPhone() { return phone; }
    public String getName() { return name; }
    public String getNickname() { return nickname; }
    public String getDepartment() { return department; }
    public String getReferralSource() { return referralSource; }
    public OAuthProvider getProvider() { return provider; }
    public String getProviderUserId() { return providerUserId; }
    public String getEmail() { return email; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // provider별 고정 로고 URL 반환
    public String getProfileImageUrl() {
        return provider != null ? provider.getLogoUrl() : null;
    }
}