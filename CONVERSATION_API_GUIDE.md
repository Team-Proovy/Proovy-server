# Conversation API Implementation Guide

## 📋 개요
Proovy-ai LangGraph 에이전트와 연동하여 사용자 기반 채팅 세션을 SSE 스트리밍 방식으로 처리하는 API 구현

## 🎯 구현된 기능

### 1. 엔티티 구조 (ERD 설계 기준)

#### ChatSession (chat_sessions)
- **chat_session_id**: 채팅 세션 ID (PK)
- **user_id**: 사용자 ID (FK to users)
- **external_thread_id**: Proovy-ai thread_id (unique, nullable)
- **status**: 세션 상태 (active, closed)
- **created_at**: 생성 시각
- **closed_at**: 종료 시각 (nullable)

#### ChatMessage (chat_messages)
- **chat_message_id**: 메시지 ID (PK)
- **chat_session_id**: 채팅 세션 ID (FK to chat_sessions)
- **role**: 메시지 역할 (USER, ASSISTANT, SYSTEM)
- **content**: 메시지 내용 (JSONB) - 텍스트 + 메타데이터 유연하게 저장
- **message_type**: 메시지 타입 (text, image, code 등)
- **created_at**: 생성 시각

#### MessageAttachment (message_attachments)
- **message_attachment_id**: 첨부 파일 ID (PK)
- **chat_message_id**: 메시지 ID (FK to chat_messages)
- **file_name**: 파일명
- **mime_type**: MIME 타입
- **storage_key**: S3 저장 키
- **source**: 파일 출처 (upload, ai_generated)
- **metadata**: 추가 메타데이터 (JSONB)
- **created_at**: 생성 시각

### 2. DTO 구조

#### 요청 DTO
- **ConversationRequest**: 클라이언트 → Spring 서버
  - `text`: 사용자 질문/지시문 (필수)
  - `latex`: LaTeX 수식 입력 (선택)
  - `mentionedAssetIds`: 첨부 자산 ID 목록 (선택)
  - `chosenFeatures`: 실행 기능 목록 (선택) - Solve, Check, Explain, Variant, Practice
  - `canvasImageIds`: 캔버스 이미지 ID 목록 (선택)

- **ProovyAiRequest**: Spring → Proovy-ai
  - `thread_id`: 기존 대화 스레드 ID
  - `user_message`: 사용자 메시지
  - `files_url`: S3 URL로 변환된 자산 목록
  - `chosen_features`: 선택된 기능 목록
  - `metadata`: 추가 메타데이터 (latex, canvas_image_ids 등)

#### 응답 DTO
- **ProovyAiStreamEvent**: SSE 이벤트 래퍼
  - `event`: 이벤트 타입 (message, metadata, [DONE] 등)
  - `data`: 이벤트 데이터 (Map)

### 3. API 엔드포인트

```
POST /api/conversations?isStream={true|false}
```

#### Query Parameter
- `isStream` (Boolean, 기본값: false)
  - `true`: SSE 스트리밍 응답 (구현 완료)
  - `false`: 단건 JSON 응답 (향후 구현)

#### 인증
- Spring Security의 `CustomUserDetails`를 통한 사용자 인증 필요

### 4. SSE 스트리밍 흐름

```
Client → Spring Controller → ChatService → WebClient → Proovy-ai (FastAPI)
                                                           ↓
Client ← SSE Events ←────────────────────────────← SSE Stream
```

1. 클라이언트가 `POST /api/conversations?isStream=true` 요청
2. Spring이 사용자 인증 및 검증
3. 사용자의 가장 최근 활성 ChatSession 조회 (없으면 생성)
4. 사용자 메시지를 ChatMessage 엔티티로 저장 (JSONB content)
5. AI 응답용 빈 ChatMessage 생성
6. 자산 ID → S3 URL 변환
7. WebClient로 Proovy-ai `/stream` 엔드포인트 호출
8. SSE 이벤트 수신 시:
   - `thread_id` 수신 → ChatSession에 저장
   - 메시지 토큰 수신 → content 누적
9. `[DONE]` 이벤트 수신 시:
   - ChatMessage content를 최종 JSONB로 저장
   - 스트림 종료

### 5. 에러 코드

| 코드 | 메시지 | HTTP Status |
|------|-----채팅 세션 또는 사용자---------|
| CONV4041 | 노트 또는 대화를 찾을 수 없거나 접근 권한이 없습니다. | 404 |
| CONV4001 | 잘못된 기능 값이 포함되어 있습니다. | 400 |
| CONV5001 | Proovy-ai 통신 중 오류가 발생했습니다. | 500 |
| CONV5002 | Proovy-ai 스트리밍 중 오류가 발생했습니다. | 500 |

### 6. 설정

#### .env
```properties
PROOVY_AI_HOST=http://localhost:8081
```

#### application.yaml
```yaml
proovy:
  ai:
    host: ${PROOVY_AI_HOST:http://localhost:8081}
```

#### WebClient
- Connection Timeout: 10초
- Response Timeout: 10분 (SSE 스트리밍 대응)
- Max In-Memory Size: 10MB

## 🔧 주요 구현 세부사항

### 자산 처리
- `mentionedAssetIds`에 포함된 자산 ID를 조회
- 사용자 권한 검증 (`userId` 일치 확인)
- S3Service를 통해 각 자산의 S3 URL 생성
- Proovy-ai에 `files_url` 필드로 전달

### 기능 검증
지원되는 기능:
- Solve
- Check
- Explain
- Variant
- Practice

잘못된 기능 요청 시 `CONV4001` 에러 반환

### 스레드 관리
- 사용자당 여러 ChatSession 생성 가능
- API 호출 시 가장 최근 활성(active) 세션 자동 사용
- 세션이 없으면 새로 생성
- Proovy-ai로부터 받은 `thread_id`를 `externalThreadId`에 저장
- 이후 같은 세션에 대한 요청은 기존 thread_id 재사용

### JSONB 활용
ChatMessage의 content 필드:
```json
{
  "text": "사용자 질문 또는 AI 응답",
  "latex": "\\int_{0}^{1} x^2 dx",
  "features": ["Solve", "Explain"],
  "mentioned_assets": [1, 2, 3]
}
```

유연한 데이터 구조로 다양한 메타데이터 저장 가능

### SSE 이벤트 파싱
Proovy-ai의 SSE 형

### 테이블 생성
```sql
CREATE TABLE chat_sessions (
    chat_session_id BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    external_thread_id VARCHAR(100) UNIQUE,
    status          VARCHAR(10) DEFAULT 'active',        -- active, closed
    created_at      TIMESTAMP DEFAULT NOW(),
    closed_at       TIMESTAMP
);

CREATE TABLE chat_messages (
    chat_message_id BIGSERIAL PRIMARY KEY,
    chat_session_id BIGINT NOT NULL REFERENCES chat_sessions(chat_session_id) ON DELETE CASCADE,
    role            VARCHAR(10) NOT NULL,                -- user, assistant, system
    content         JSONB,                               -- 텍스트 + 메타데이터 유연하게
    message_type    VARCHAR(50) NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE message_attachments (
    message_attachment_id BIGSERIAL PRIMARY KEY,
    chat_message_id       BIGINT NOT NULL REFERENCES chat_messages(chat_message_id) ON DELETE CASCADE,
    file_name             VARCHAR(255),
    mime_type             VARCHAR(100),
    storage_key           VARCHAR(500),
    source                VARCHAR(20) NOT NULL,          -- upload, ai_generated
    metadata              JSONB,
    created_at            TIMESTAMP DEFAULT NOW()
)
data: {"thread_id": "thread_abc123"}

event: [DONE]
data: 
```

Spring에서 이를 파싱하여 `ProovyAiStreamEvent`로 변환 후 클라이언트에 중계

## 📊 데이터베이스 스키마 변경

### Conversation 테이블
```sql
ALTER TABLE conversations 
ADD COLUMN external_thread_id VARCHAR(100) UNIQUE;
```

## 🧪 테스트 예시

### cURL 요청
```bash
curl -X POST 'http://localhost:8080/api/conversations/1?isStream=true' \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "이 문제를 풀어줘",
    "latex": "\\int_{0}^{1} x^2 dx",
    "mentionedAssetIds": [10, 20],
    "chosenFeatures": ["Solve", "Explain"]
  }'
```

### 예상 SSE 응답
```
event: message
data: {"content":"적분을","type":sessionId}/messages`
   - 채팅 세션 히스토리 페이징 조회

4. **세션 관리 API**
   - `GET /api/conversations`: 사용자의 모든 세션 목록
   - `DELETE /api/conversations/{sessionId}`: 세션 종료
event: message
data: {"content":" 계산하면","type":"text"}

event: metadata
data: {"thread_id":"thread_abc123"}

event: message
data: {"content":" 답은 1/3입니다.","type":"text"}

event: [DONE]
data: 
```

## 🚀 향후 확장 포인트

1. **단건 응답 모드 (`isStream=false`)**
   - Proovy-ai `/invoke` 엔드포인트 호출
   - 최종 응답만 JSON으로 반환

2. **캔버스 이미지 처리**
   - `canvasImageIds` 구조 분석
   - 이미지 저장 및 URL 변환 로직 추가

3. **메시지 히스토리 조회 API**
   - `GET /api/conversations/{conversationId}/messages`
   - 대화 히스토리 페이징 조회

4. **Proovy-ai 응답 검증**
   - 응답 형식 검증 로직 강화
   - 에러 응답 핸들링 개선

5. **메트릭 및 모니터링**
   - SSE 스트리밍 성능 모니터링
   - Proovy-ai 응답 시간 추적

## ⚠️ 주의사항

1. **트랜잭션 관리**
   - 스트리세션에 대한 동시 요청 시 race condition 가능
   - 필요 시 낙관적 락 또는 분산 락 도입 고려

3. **타임아웃 설정**
   - Proovy-ai 응답이 5분 이상 걸릴 경우 타임아웃
   - 필요 시 `application.yaml`에서 조정

4. **JSONB 데이터 크기**
   - 매우 긴 대화의 경우 content JSONB가 커질 수 있음
   - PostgreSQL JSONB는 충분히 큰 데이터를 지원

5. **세션 정리**
   - 오래된 closed 세션은 주기적으로 아카이빙 또는 삭제 필요
hatSession 엔티티 생성 (ERD 기준)
- [x] ChatMessage 엔티티 생성 (JSONB content)
- [x] MessageAttachment 엔티티 생성
- [x] Repository 인터페이스 생성
- [x] ChatService 인터페이스 및 구현
- [x] ConversationController 업데이트
- [x] JSONB 의존성 추가 (hypersistence-utils)
- [x] ErrorCode 업데이트
- [x] WebClient 설정 업데이트 (타임아웃, 메모리)
- [x] .env에 PROOVY_AI_HOST 추가
- [x] application.yaml에 proovy.ai.host 설정 추가
- [x] Swagger 문서화
- [ ] 데이터베이스 마이그레이션 실행성
- [x] ErrorCode 추가 (CONV4041, CONV4001, CONV5001, CONV5002)
- [x] ConversationService 인터페이스 및 구현
- [x] ConversationController SSE 엔드포인트 구현
- [x] WebClient 설정 업데이트 (타임아웃, 메모리)
- [x] .env에 PROOVY_AI_HOST 추가
- [x] application.yaml에 proovy.ai.host 설정 추가
- [x] Swagger 문서화
- [ ] 단위 테스트 작성 (향후)
- [ ] 통합 테스트 작성 (향후)
