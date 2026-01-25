package com.proovy.domain.auth.dto.response;

import lombok.Builder;

@Builder
public record SignupCompleteResponse(
        SignupUserDto user,
        TokenDto token,
        Integer welcomeCredit
) {
    @Builder
    public record SignupUserDto(
            Long userId,
            String email,
            String name,
            String nickname,
            String department,
            String profileImageUrl,
            String createdAt
    ) {}
}
