package com.proovy.domain.auth.converter;

import com.proovy.domain.user.entity.Role;
import com.proovy.domain.user.entity.User;

public class AuthConverter {

    private AuthConverter() {}

    public static User toUser(String userKey) {
        return User.builder()
                .userKey(userKey)
                .role(Role.USER)
                .build();
    }
}
