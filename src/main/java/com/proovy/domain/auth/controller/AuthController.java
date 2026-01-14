package com.proovy.domain.auth.controller;

import com.proovy.domain.auth.service.AuthService;
import com.proovy.domain.user.entity.User;
import com.proovy.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("")
public class AuthController {

    private final AuthService authService;

    @GetMapping("/auth/login/kakao")
    public ApiResponse<AuthResult> kakaoLogin(
            @RequestParam("code") String accessCode,
            HttpServletResponse httpServletResponse
    ) {
        User user = authService.oAuthLogin(accessCode, httpServletResponse);

        // 여기서는 예시로 응답 DTO를 심플하게 구성
        return ApiResponse.success(new AuthResult(user.getUserId(), user.getEmail(), user.getName()));
    }

    public record AuthResult(Long userId, String email, String name) {}
}
