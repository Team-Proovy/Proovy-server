package com.proovy.domain.user.entity;

import com.proovy.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_phone", columnNames = {"phone"})
        }
)
public class User extends BaseTimeEntity {

    @Getter
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Getter
    @Column(name = "phone", length = 20, nullable = false)
    private String phone;

    @Column(name = "name", length = 50)
    private String name;

    @Getter
    @Column(name = "nickname", length = 30, nullable = false)
    private String nickname;

    @Column(name = "department", length = 100)
    private String department;

    @Column(name = "referral_source", length = 20)
    private String referralSource;

//    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
//    private List<AuthAccount> authAccounts = new ArrayList<>();
//
//    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
//    private List<UserPlan> userPlans = new ArrayList<>();

    protected User() {
    }

    public User(String phone, String nickname) {
        this.phone = phone;
        this.nickname = nickname;
    }

}