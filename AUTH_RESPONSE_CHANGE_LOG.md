# 카카오 로그인 응답 형식 변경 완료

## ✅ 변경 사항

### 1. 응답 DTO 생성
**파일**: `AuthResponse.java`
- `loginType`: "LOGIN" 또는 "SIGNUP"
- `user`: 사용자 정보 (userId, nickname, phoneVerified)
- `token`: JWT 토큰 정보 (accessToken, refreshToken, 만료 시간)

### 2. JwtUtil 개선
**파일**: `JwtUtil.java`
- ✅ `createRefreshToken()` 메서드 추가
- ✅ `getAccessTokenExpiresIn()` 메서드 추가 (초 단위 반환)
- ✅ `getRefreshTokenExpiresIn()` 메서드 추가 (초 단위 반환)
- ✅ refreshToken 만료 시간 설정 (7일 = 604800000ms)

### 3. AuthService 개선
**파일**: `AuthService.java`
- ✅ `AuthResult` 내부 클래스 추가 (User, 토큰들, loginType 포함)
- ✅ 신규 가입 vs 기존 로그인 구분 로직 추가
- ✅ accessToken과 refreshToken 동시 생성

### 4. UserRepository 개선
**파일**: `UserRepository.java`
- ✅ `existsByUserKey()` 메서드 추가

### 5. AuthController 변경
**파일**: `AuthController.java`
- ✅ HttpServletResponse 제거 (더 이상 헤더에 토큰 전송하지 않음)
- ✅ AuthResponse DTO로 응답

---

## 📄 응답 형식

### 기존 유저 로그인 시
```json
{
  "isSuccess": true,
  "code": "common200",
  "message": "로그인에 성공했습니다.",
  "result": {
    "loginType": "LOGIN",
    "user": {
      "userId": 1,
      "nickname": "홍길동",
      "phoneVerified": false
    },
    "token": {
      "grantType": "Bearer",
      "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
      "accessTokenExpiresIn": 3600,
      "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
      "refreshTokenExpiresIn": 604800
    }
  }
}
```

### 신규 회원가입 시
```json
{
  "isSuccess": true,
  "code": "common200",
  "message": "로그인에 성공했습니다.",
  "result": {
    "loginType": "SIGNUP",
    "user": {
      "userId": 2,
      "nickname": "user_4697344388",
      "phoneVerified": false
    },
    "token": {
      "grantType": "Bearer",
      "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
      "accessTokenExpiresIn": 3600,
      "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
      "refreshTokenExpiresIn": 604800
    }
  }
}
```

---

## 🔑 토큰 정보

### Access Token
- **유효 시간**: 3600초 (1시간)
- **포함 정보**: userKey, role
- **용도**: API 인증

### Refresh Token
- **유효 시간**: 604800초 (7일)
- **포함 정보**: userKey
- **용도**: Access Token 갱신

---

## 🎯 주요 특징

1. **loginType 구분**
   - 최초 로그인: "SIGNUP"
   - 재로그인: "LOGIN"

2. **phoneVerified 상태**
   - phone이 null이거나 빈 문자열: false
   - phone에 값이 있음: true

3. **토큰 응답 방식 변경**
   - 이전: Response Header의 Authorization
   - 현재: Response Body의 token 객체

4. **만료 시간 단위**
   - 설정 파일: 밀리초
   - 응답: 초 (클라이언트가 계산하기 쉽게)

---

## 🧪 테스트

### Request
```
GET http://localhost:8080/auth/login/kakao?code=YOUR_KAKAO_AUTH_CODE
```

### Response Headers
```
Content-Type: application/json
```

### Response Body
위의 JSON 형식 참조

---

## ⚠️ 참고 사항

- 카카오에서 닉네임을 제공하지 않으면 "user_[userKey]" 형태로 자동 생성
- phone 정보는 현재 수집하지 않으므로 phoneVerified는 항상 false
- JWT 토큰은 stateless이므로 서버에 저장하지 않음
- Refresh Token을 이용한 갱신 엔드포인트는 별도 구현 필요

