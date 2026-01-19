# Proovy API

이공계 대학생을 위한 AI 튜터 서비스 백엔드

## 기술 스택

- **언어**: Java 21
- **프레임워크**: Spring Boot 3.x
- **데이터베이스**: PostgreSQL
- **캐시**: Redis
- **빌드 도구**: Gradle
- **API 문서**: Springdoc OpenAPI (Swagger)
- **아키텍처**: DDD (Domain-Driven Design)

## 프로젝트 구조

```
proovy-api/
├── src/
│   ├── main/
│   │   ├── java/com/proovy/
│   │   │   ├── ProovyApiApplication.java
│   │   │   ├── global/                    # 전역 설정 및 공통 기능
│   │   │   │   ├── config/                # 설정 클래스
│   │   │   │   │   └── SwaggerConfig.java
│   │   │   │   ├── response/              # API 공통 응답 포맷
│   │   │   │   │   ├── ApiResponse.java
│   │   │   │   │   └── ErrorCode.java
│   │   │   │   ├── exception/             # 전역 예외 처리
│   │   │   │   │   ├── BusinessException.java
│   │   │   │   │   └── GlobalExceptionHandler.java
│   │   │   │   ├── security/              # 인증/인가 설정 (JWT, OAuth)
│   │   │   │   ├── util/                  # 유틸리티 클래스
│   │   │   │   └── infra/                 # 외부 인프라 연동
│   │   │   │       ├── s3/                # AWS S3 연동
│   │   │   │       └── http/              # HTTP 클라이언트
│   │   │   │
│   │   │   └── domain/                    # 도메인별 비즈니스 로직 (DDD)
│   │   │       ├── auth/                  # 인증 도메인
│   │   │       │   ├── controller/        # 소셜 로그인, 회원가입, 토큰 갱신
│   │   │       │   ├── service/
│   │   │       │   ├── dto/
│   │   │       │   │   ├── request/
│   │   │       │   │   └── response/
│   │   │       │   ├── provider/          # OAuth 제공자별 구현
│   │   │       │   └── entity/
│   │   │       │
│   │   │       ├── user/                  # 사용자 도메인
│   │   │       │   ├── controller/        # 프로필 조회/수정, 회원 탈퇴
│   │   │       │   ├── service/
│   │   │       │   ├── dto/
│   │   │       │   │   ├── request/
│   │   │       │   │   └── response/
│   │   │       │   ├── entity/            # User, Plan 엔티티
│   │   │       │   └── repository/
│   │   │       │
│   │   │       ├── note/                  # 노트 도메인
│   │   │       │   ├── controller/        # 노트 CRUD, 목록 조회
│   │   │       │   ├── service/
│   │   │       │   ├── dto/
│   │   │       │   │   ├── request/
│   │   │       │   │   └── response/
│   │   │       │   ├── entity/            # Note 엔티티
│   │   │       │   └── repository/
│   │   │       │
│   │   │       ├── asset/                 # 자산 도메인 (파일 관리)
│   │   │       │   ├── controller/        # 파일 업로드/다운로드/삭제
│   │   │       │   ├── service/           # S3 연동, 스토리지 관리
│   │   │       │   ├── dto/
│   │   │       │   │   ├── request/
│   │   │       │   │   └── response/
│   │   │       │   ├── entity/            # Asset 엔티티
│   │   │       │   └── repository/
│   │   │       │
│   │   │       ├── ocr/                   # OCR 도메인
│   │   │       │   ├── controller/        # OCR 처리 API
│   │   │       │   ├── service/           # AI 서버 연동
│   │   │       │   ├── dto/
│   │   │       │   │   ├── request/
│   │   │       │   │   └── response/
│   │   │       │   └── entity/            # OcrResult 엔티티
│   │   │       │
│   │   │       ├── conversation/          # 대화 도메인
│   │   │       │   ├── controller/        # 대화 전송, 목록 조회
│   │   │       │   ├── service/           # AI 응답 생성, 도구 실행
│   │   │       │   ├── dto/
│   │   │       │   │   ├── request/
│   │   │       │   │   └── response/
│   │   │       │   ├── entity/            # Conversation, Message 엔티티
│   │   │       │   └── repository/
│   │   │       │
│   │   │       └── credit/                # 크레딧 도메인
│   │   │           ├── controller/        # 크레딧 조회, 사용 내역
│   │   │           ├── service/           # 크레딧 차감/충전 로직
│   │   │           ├── dto/
│   │   │           │   └── response/
│   │   │           ├── entity/            # Credit, CreditTransaction 엔티티
│   │   │           └── repository/
│   │   │
│   │   └── resources/
│   │       ├── application.yaml           # 애플리케이션 설정
│   │       ├── db/migration/              # DB 마이그레이션
│   │       └── static/                    # 정적 리소스
│   │
│   └── test/                              # 테스트 코드
│       └── java/com/proovy/
│
├── build.gradle                           # Gradle 빌드 설정
├── settings.gradle
└── README.md
```

## 도메인별 기능 설명

### 1. Auth 도메인
- 소셜 로그인 (OAuth)
- 휴대폰 인증 발송/확인
- 회원가입 완료
- 토큰 갱신 (Access/Refresh Token)
- 로그아웃

### 2. User 도메인
- 내 정보 조회 (프로필, 요금제, 크레딧)
- 내 정보 수정 (닉네임, 학과 등)
- 회원 탈퇴

### 3. Note 도메인
- 노트 목록 조회 (페이징)
- 노트 상세 조회 (채팅방 입장)
- 노트 제목 수정
- 노트 삭제

### 4. Asset 도메인 (파일 관리)
- 파일 업로드 (Presigned URL 방식)
- 파일 업로드 완료 확인
- 파일 다운로드 URL 발급
- 파일 뷰어 URL 발급
- 파일 목록 조회
- 파일 삭제 (단일/일괄)
- 전체 스토리지 현황 조회

### 5. OCR 도메인
- 이미지/PDF에서 텍스트 추출
- OCR 처리 상태 조회
- AI 서버 연동

### 6. Conversation 도메인
- 사용 가능한 도구 목록 조회
- 멘션용 파일 검색 (자동완성)
- 캔버스 이미지 업로드
- 대화 전송 (질문 + AI 응답)
- 대화 목록 조회 (검색)
- 대화 상세 조회

### 7. Credit 도메인
- 크레딧 잔액 조회
- 크레딧 사용 내역 조회 (페이징)
- 크레딧 비용 정보 조회 (OCR, 문제 풀이 등)

## API 문서

애플리케이션 실행 후 다음 URL에서 API 문서를 확인할 수 있습니다:

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **API Docs (JSON)**: http://localhost:8080/api-docs