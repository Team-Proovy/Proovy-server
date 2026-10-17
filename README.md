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
- Note가 유일한 대화 단위 (`conversationId = noteId`), 메시지는 Note에 직접 연결
- 캔버스 이미지 업로드
- 대화 검색
- 신규 run 접수·SSE·취소는 S07 후속 작업 (구형 `/stream/v2` 경로는 제거됨)

### 8. Credit 도메인
- 크레딧 잔액 조회
- 크레딧 사용 (차감)
- 크레딧 사용 내역 조회 (페이징, 필터링)
- 크레딧 비용 정보 조회
- 예상 크레딧 비용 조회

### 9. Run 도메인 (S07 저장 기반)
- `ai_runs`·`ai_run_events`·`run_asset_dependencies`, `verified_solutions`, `request_usage_*` ledger
- 상태 전이·불변 snapshot·Active Run 제약은 DB baseline에서 강제

## 데이터베이스

- 제품 schema는 빈 DB에서 `db/migration/V1__product_baseline.sql`로 생성한다. 과거 migration·데이터 이관은 없으며 이후 변경은 `V2__...`부터 versioned migration으로 추가한다.
- `baseline-on-migrate`는 꺼져 있어 과거 schema/history가 있는 DB에서는 기동이 실패한다.
- Server ↔ Brain DB role 경계는 `db/provisioning/role-boundary.sql`(Flyway 미실행, 운영자 적용)로 고정한다.

### DB 통합 테스트

실제 Postgres에 테스트마다 격리 database를 만들어 제약·role·baseline을 검사한다 (superuser 접속 필요).

```bash
docker run -d --name proovy-postgres-test -p 127.0.0.1:21432:5432 \
  -e POSTGRES_USER=proovy -e POSTGRES_PASSWORD=proovy123 postgres:15-alpine
PROOVY_TEST_PG_URL=jdbc:postgresql://127.0.0.1:21432/postgres \
PROOVY_TEST_PG_USER=proovy PROOVY_TEST_PG_PASSWORD=proovy123 \
PROOVY_REQUIRE_DB_TESTS=true ./gradlew test
```

환경변수가 없으면 DB 테스트는 skip(미실행)되고, `PROOVY_REQUIRE_DB_TESTS=true`(CI)이면 실패한다.

**전용 일회용 Postgres 클러스터에서만 실행한다.** role 경계 테스트는 cluster 단위 role(`proovy_server`, `brain_*`)을
만들고 비밀번호를 덮어쓰며, database를 지워도 role은 남는다. 공유·운영 클러스터를 `PROOVY_TEST_PG_URL`로 지정하지 않는다.

## API 문서

- **Swagger UI**: https://api.proovy.ai.kr/swagger-ui/index.html#/
