package com.proovy.domain.auth.service;

import com.proovy.domain.auth.dto.response.GoogleUserInfo;
import com.proovy.domain.auth.dto.response.KakaoUserInfo;
import com.proovy.domain.auth.dto.response.NaverUserInfo;
import com.proovy.domain.auth.dto.response.TokenDto;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;
    private final long signupTokenExpiration;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration}") long accessTokenExpiration,
            @Value("${jwt.refresh-token-expiration}") long refreshTokenExpiration,
            @Value("${jwt.signup-token-expiration}") long signupTokenExpiration
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiration = accessTokenExpiration * 1000;
        this.refreshTokenExpiration = refreshTokenExpiration * 1000;
        this.signupTokenExpiration = signupTokenExpiration * 1000;
    }

    /**
     * Access Token + Refresh Token 동시 생성
     */
    public TokenDto generateTokens(Long userId) {
        Date now = new Date();

        String accessToken = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", "access")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenExpiration))
                .signWith(secretKey)
                .compact();

        String refreshToken = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + refreshTokenExpiration))
                .signWith(secretKey)
                .compact();

        return TokenDto.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .accessTokenExpiresIn(accessTokenExpiration / 1000)
                .refreshTokenExpiresIn(refreshTokenExpiration / 1000)
                .build();
    }

    /**
     * 회원가입용 임시 토큰 생성 (카카오 정보 포함)
     * 이름은 회원가입 시 직접 입력받으므로 토큰에 포함하지 않음
     */
    public String generateSignupToken(KakaoUserInfo kakaoInfo) {
        Date now = new Date();

        return Jwts.builder()
                .subject(kakaoInfo.id())
                .claim("type", "signup")
                .claim("provider", "KAKAO")
                .claim("email", kakaoInfo.email())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + signupTokenExpiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 회원가입용 임시 토큰 생성 (네이버 정보 포함)
     * 네이버는 이름, 휴대폰 번호도 포함
     */
    public String generateSignupToken(NaverUserInfo naverInfo) {
        Date now = new Date();

        return Jwts.builder()
                .subject(naverInfo.id())
                .claim("type", "signup")
                .claim("provider", "NAVER")
                .claim("email", naverInfo.email())
                .claim("name", naverInfo.name())
                .claim("mobile", naverInfo.mobile())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + signupTokenExpiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 회원가입용 임시 토큰 생성 (구글 정보 포함)
     * 구글은 이름도 포함 (null 가능)
     */
    public String generateSignupToken(GoogleUserInfo googleInfo) {
        Date now = new Date();

        return Jwts.builder()
                .subject(googleInfo.id())
                .claim("type", "signup")
                .claim("provider", "GOOGLE")
                .claim("email", googleInfo.email())
                .claim("name", googleInfo.name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + signupTokenExpiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 토큰에서 userId 추출
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return Long.parseLong(claims.getSubject());
    }

    /**
     * 토큰 유효성 검증
     */
    public boolean validateToken(String token, String expectedType) {
        try {
            Claims claims = parseToken(token);
            if (expectedType != null && !expectedType.equals(claims.get("type", String.class))) {
                throw new BusinessException(ErrorCode.AUTH4013);
            }
            return true;
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.AUTH4012);
        } catch (JwtException e) {
            throw new BusinessException(ErrorCode.AUTH4013);
        }
    }

    /**
     * Access Token 유효성 검증
     */
    public boolean validateAccessToken(String token) {
        return validateToken(token, "access");
    }

    /**
     * Refresh Token 유효성 검증
     */
    public boolean validateRefreshToken(String token) {
        return validateToken(token, "refresh");
    }

    /**
     * Signup Token에서 카카오 정보 추출
     */
    public KakaoUserInfo getKakaoInfoFromSignupToken(String token) {
        Claims claims = parseToken(token);

        if (!"signup".equals(claims.get("type", String.class))) {
            throw new BusinessException(ErrorCode.AUTH4013);
        }

        return KakaoUserInfo.builder()
                .id(claims.getSubject())
                .email(claims.get("email", String.class))
                .build();
    }

    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
