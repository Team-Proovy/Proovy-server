package com.proovy.domain.user.entity;

import com.proovy.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_key", columnNames = {"user_key"})
        }
)
public class User extends BaseTimeEntity {

    @Getter
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Getter
    @Column(name = "phone", length = 20, nullable = true)
    private String phone;

    @Getter
    @Column(name = "name", length = 50, nullable = true)
    private String name;

    @Getter
    @Column(name = "nickname", length = 30)
    private String nickname;

    @Column(name = "department", length = 100, nullable = true)
    private String department;

    @Column(name = "referral_source", length = 20, nullable = true)
    private String referralSource;

    @Getter
    @Column(name = "email", length = 100, nullable = true)
    private String email;

    @Getter
    @Column(name = "profile", length = 500, nullable = true)
    private String profile;

    @Getter
    @Column(name = "user_key", length = 100, unique = true)
    private String userKey;

    @Getter
    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20)
    private Role role;

//    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
//    private List<AuthAccount> authAccounts = new ArrayList<>();
//
//    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
//    private List<UserPlan> userPlans = new ArrayList<>();

    protected User() {
    }

    @Builder
    public User(String phone, String name, String nickname, String email, String profile,
                String department, String referralSource, String userKey, Role role) {
        this.phone = phone;
        this.name = name;
        this.nickname = nickname;
        this.email = email;
        this.profile = profile;
        this.department = department;
        this.referralSource = referralSource;
        this.userKey = userKey;
        this.role = role != null ? role : Role.USER;
    }

}