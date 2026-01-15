package com.proovy.global.security.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class JwtUtil {

    private final String secret;
    private final Duration accessExpiration;
    private final Duration refreshExpiration;
    private SecretKey secretKey;

    public JwtUtil(
            @Value("${jwt.key}") String secret,
            @Value("${jwt.access-token-expiration}") Long accessExpirationMillis,
            @Value("${jwt.refresh-token-expiration}") Long refreshExpirationMillis
    ) {
        this.secret = secret;
        this.accessExpiration = Duration.ofMillis(accessExpirationMillis);
        this.refreshExpiration = Duration.ofMillis(refreshExpirationMillis);
    }

    @PostConstruct
    void init() {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(String subjectEmail, String role) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + accessExpiration.toMillis());

        return Jwts.builder()
                .setSubject(subjectEmail)
                .claim("role", role)
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(secretKey)
                .compact();
    }

    public String createRefreshToken(String subjectEmail) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + refreshExpiration.toMillis());

        return Jwts.builder()
                .setSubject(subjectEmail)
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(secretKey)
                .compact();
    }

    public long getAccessTokenExpiresIn() {
        return accessExpiration.toSeconds();
    }

    public long getRefreshTokenExpiresIn() {
        return refreshExpiration.toSeconds();
    }
}
