package com.proovy.domain.user.entity;

import com.proovy.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_key", columnNames = {"user_key"})
        }
)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "name", length = 50)
    private String name;

    @Column(name = "nickname", length = 30, nullable = false)
    private String nickname;

    @Column(name = "department", length = 100)
    private String department;

    @Column(name = "referral_source", length = 20)
    private String referralSource;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "profile", length = 500)
    private String profile;

    @Column(name = "user_key", length = 100, nullable = false, unique = true)
    private String userKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private Role role;

    // 연관관계는 추후 필요 시 활성화
    // @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    // private List<AuthAccount> authAccounts = new ArrayList<>();
    //
    // @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    // private List<UserPlan> userPlans = new ArrayList<>();

    @Builder
    public User(
            String phone,
            String name,
            String nickname,
            String email,
            String profile,
            String department,
            String referralSource,
            String userKey,
            Role role
    ) {
        this.phone = phone;
        this.name = name;
        this.nickname = nickname;
        this.email = email;
        this.profile = profile;
        this.department = department;
        this.referralSource = referralSource;
        this.userKey = userKey;
        this.role = (role != null) ? role : Role.USER;
    }
}
