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

## 배포 환경

| 서비스 | URL |
|--------|-----|
| 프론트엔드 | https://proovy.ai.kr/ |
| API 게이트웨이 | https://api.proovy.ai.kr/ |
| Spring API | https://api.proovy.ai.kr/api/* |
| AI API | https://api.proovy.ai.kr/ai/* |
| Swagger UI | https://api.proovy.ai.kr/swagger-ui/index.html#/ |

## 프로젝트 구조

```
proovy-api/
├── src/
│   ├── main/
│   │   ├── java/com/proovy/
│   │   │   ├── ProovyApiApplication.java
│   │   │   ├── global/                    # 전역 설정 및 공통 기능
│   │   │   │   ├── config/                # 설정 클래스
│   │   │   │   ├── response/              # API 공통 응답 포맷
│   │   │   │   ├── exception/             # 전역 예외 처리
│   │   │   │   ├── security/              # 인증/인가 설정 (JWT, OAuth)
│   │   │   │   ├── util/                  # 유틸리티 클래스
│   │   │   │   ├── tool/                  # 도구 설정 및 관리
│   │   │   │   └── infra/                 # 외부 인프라 연동
│   │   │   │       ├── s3/                # AWS S3 연동
│   │   │   │       └── http/              # HTTP 클라이언트
│   │   │   │
│   │   │   └── domain/                    # 도메인별 비즈니스 로직 (DDD)
│   │   │       ├── auth/                  # 인증 도메인
│   │   │       │   ├── controller/        # 소셜 로그인, 회원가입, 토큰 갱신
│   │   │       │   ├── service/
│   │   │       │   ├── dto/
│   │   │       │   ├── provider/          # OAuth 제공자별 구현
│   │   │       │   └── entity/
│   │   │       │
│   │   │       ├── user/                  # 사용자 도메인
│   │   │       │   ├── controller/        # 프로필 조회/수정, 회원 탈퇴
│   │   │       │   ├── service/
│   │   │       │   ├── dto/
│   │   │       │   ├── entity/            # User, Plan 엔티티
│   │   │       │   └── repository/
│   │   │       │
│   │   │       ├── note/                  # 노트 도메인
│   │   │       │   ├── controller/        # 노트 CRUD, 목록 조회
│   │   │       │   ├── service/
│   │   │       │   ├── dto/
│   │   │       │   ├── entity/            # Note 엔티티
│   │   │       │   └── repository/
│   │   │       │
│   │   │       ├── asset/                 # 자산 도메인 (파일 관리)
│   │   │       │   ├── controller/        # 파일 업로드/다운로드/삭제
│   │   │       │   ├── service/           # S3 연동, 스토리지 관리
│   │   │       │   ├── dto/
│   │   │       │   ├── entity/            # Asset 엔티티
│   │   │       │   └── repository/
│   │   │       │
│   │   │       ├── storage/               # 스토리지 도메인
│   │   │       │   ├── controller/        # 스토리지 현황 조회
│   │   │       │   ├── service/
│   │   │       │   └── dto/
│   │   │       │
│   │   │       ├── ocr/                   # OCR 도메인
│   │   │       │   ├── controller/        # OCR 처리 API
│   │   │       │   ├── service/           # AI 서버 연동
│   │   │       │   ├── dto/
│   │   │       │   └── entity/            # OcrResult 엔티티
│   │   │       │
│   │   │       ├── conversation/          # 대화 도메인
│   │   │       │   ├── controller/        # 대화 전송, 목록 조회
│   │   │       │   ├── service/           # AI 응답 생성, 도구 실행
│   │   │       │   ├── dto/
│   │   │       │   ├── entity/            # Conversation, Message 엔티티
│   │   │       │   └── repository/
│   │   │       │
│   │   │       ├── credit/                # 크레딧 도메인
│   │   │       │   ├── controller/        # 크레딧 조회, 사용, 내역
│   │   │       │   ├── service/           # 크레딧 차감/충전 로직
│   │   │       │   ├── dto/
│   │   │       │   │   ├── request/
│   │   │       │   │   └── response/
│   │   │       │   ├── entity/            # CreditBalance, CreditHistory 엔티티
│   │   │       │   └── repository/
│   │   │       │
│   │   │       └── embedding/             # 임베딩 도메인
│   │   │           ├── NoteEmbedding.java
│   │   │           ├── NoteEmbeddingRepository.java
│   │   │           └── service/
│   │   │
│   │   └── resources/
│   │       ├── application.yaml           # 애플리케이션 설정
│   │       └── db/migration/              # DB 마이그레이션
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

### 5. Storage 도메인
- 전체 스토리지 현황 조회

### 6. OCR 도메인
- 이미지/PDF에서 텍스트 추출
- OCR 처리 상태 조회
- AI 서버 연동

### 7. Conversation 도메인
- 사용 가능한 도구 목록 조회
- 멘션용 파일 검색 (자동완성)
- 캔버스 이미지 업로드
- 대화 전송 (질문 + AI 응답, SSE 스트리밍)
- 대화 목록 조회 (검색)
- 대화 상세 조회

### 8. Credit 도메인
- 크레딧 잔액 조회
- 크레딧 사용 (차감)
- 크레딧 사용 내역 조회 (페이징, 필터링)
- 크레딧 비용 정보 조회
- 예상 크레딧 비용 조회

### 9. Embedding 도메인
- 노트 임베딩 저장/조회
- 유사 노트 검색

## API 문서

- **Swagger UI**: https://api.proovy.ai.kr/swagger-ui/index.html#/
