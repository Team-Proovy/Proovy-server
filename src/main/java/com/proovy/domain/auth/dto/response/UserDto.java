package com.proovy.domain.auth.dto.response;

import com.proovy.domain.user.entity.User;
import lombok.Builder;

@Builder
public record UserDto(
        Long id,
        String name,
        String nickname,
        String email,
        String profileImageUrl   // provider별 고정 로고 URL
) {
    public static UserDto from(User user) {
        return UserDto.builder()
                .id(user.getId())
                .name(user.getName())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .profileImageUrl(user.getProfileImageUrl())
                .build();
    }
}