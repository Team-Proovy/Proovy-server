package com.proovy.domain.user.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Builder
    public User(String phone, String name, String nickname, String department, String referralSource) {
        this.phone = phone;
        this.name = name;
        this.nickname = nickname;
        this.department = department;
        this.referralSource = referralSource;
    }
}
