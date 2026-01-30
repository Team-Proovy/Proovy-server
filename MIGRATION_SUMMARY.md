# ERD 기반 Conversation 구조 변경 완료

## 📋 변경 사항 요약

### 기존 구조 → 새로운 ERD 구조

#### 엔티티 변경
| 기존 | 새로운 | 주요 변경사항 |
|------|--------|--------------|
| Conversation | ChatSession | note_id → user_id, status/closed_at 추가 |
| Message | ChatMessage | content TEXT → JSONB, status 제거, message_type 추가 |
| MessageAsset | MessageAttachment | 필드명 표준화, JSONB metadata 추가 |

### API 엔드포인트 변경
- **기존**: `POST /api/conversations/{noteId}?isStream=true`
- **새로운**: `POST /api/conversations?isStream=true`
- 노트 기반 → 사용자 기반 채팅으로 변경

## 🆕 새로 생성된 파일

### Entity
- ✅ [ChatSession.java](src/main/java/com/proovy/domain/conversation/entity/ChatSession.java)
- ✅ [ChatSessionStatus.java](src/main/java/com/proovy/domain/conversation/entity/ChatSessionStatus.java)
- ✅ [ChatMessage.java](src/main/java/com/proovy/domain/conversation/entity/ChatMessage.java)
- ✅ [MessageAttachment.java](src/main/java/com/proovy/domain/conversation/entity/MessageAttachment.java)

### Repository
- ✅ [ChatSessionRepository.java](src/main/java/com/proovy/domain/conversation/repository/ChatSessionRepository.java)
- ✅ [ChatMessageRepository.java](src/main/java/com/proovy/domain/conversation/repository/ChatMessageRepository.java)
- ✅ [MessageAttachmentRepository.java](src/main/java/com/proovy/domain/conversation/repository/MessageAttachmentRepository.java)

### Service
- ✅ [ChatService.java](src/main/java/com/proovy/domain/conversation/service/ChatService.java)
- ✅ [ChatServiceImpl.java](src/main/java/com/proovy/domain/conversation/service/ChatServiceImpl.java)

## 🗑️ 삭제/복원된 파일

### Note 도메인 호환성을 위해 복원
Note 도메인(NoteService.createNote)에서 계속 사용하기 위해 다음 파일들을 복원했습니다:

- ✅ Conversation.java (Note 전용, conversations 테이블)
- ✅ Message.java (Note 전용, messages 테이블)
- ✅ MessageStatus.java
- ✅ ConversationRepository.java
- ✅ MessageRepository.java

**중요**: 이 엔티티들은 **Note 도메인 전용**입니다. 채팅 기능은 ChatSession/ChatMessage를 사용하세요.

### 기존 유지 (공통 사용)
- ✅ MessageAsset.java
- ✅ MessageRole.java
- ✅ MessageTool.java
- ✅ MessageAssetRepository.java
- ✅ MessageToolRepository.java

## 🔧 수정된 파일

### 설정 파일
- ✅ [build.gradle](build.gradle) - JSONB 의존성 추가 (`hypersistence-utils-hibernate-63:3.7.3`)
- ✅ [ErrorCode.java](src/main/java/com/proovy/global/response/ErrorCode.java) - CONV4041 메시지 수정

### Controller
- ✅ [ConversationController.java](src/main/java/com/proovy/domain/conversation/controller/ConversationController.java)
  - `noteId` 파라미터 제거
  - `ChatService` 사용

### 문서
- ✅ [CONVERSATION_API_GUIDE.md](CONVERSATION_API_GUIDE.md) - 전체 업데이트
- ✅ [CONVERSATION_TEST_GUIDE.md](CONVERSATION_TEST_GUIDE.md) - 전체 업데이트

## 📊 데이터베이스 마이그레이션

### 신규 테이블 생성
채팅 기능을 위한 새로운 테이블 생성:

```sql
CREATE TABLE chat_sessions (
    chat_session_id BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    external_thread_id VARCHAR(100) UNIQUE,
    status          VARCHAR(10) DEFAULT 'active',
    created_at      TIMESTAMP DEFAULT NOW(),
    closed_at       TIMESTAMP
);

CREATE TABLE chat_messages (
    chat_message_id BIGSERIAL PRIMARY KEY,
    chat_session_id BIGINT NOT NULL REFERENCES chat_sessions(chat_session_id) ON DELETE CASCADE,
    role            VARCHAR(10) NOT NULL,
    content         JSONB,
    message_type    VARCHAR(50) NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE message_attachments (
    message_attachment_id BIGSERIAL PRIMARY KEY,
    chat_message_id       BIGINT NOT NULL REFERENCES chat_messages(chat_message_id) ON DELETE CASCADE,
    file_name             VARCHAR(255),
    mime_type             VARCHAR(100),
    storage_key           VARCHAR(500),
    source                VARCHAR(20) NOT NULL,
    metadata              JSONB,
    created_at            TIMESTAMP DEFAULT NOW()
);

-- 인덱스 생성
CREATE INDEX idx_chat_sessions_user_status ON chat_sessions(user_id, status);
CREATE INDEX idx_chat_sessions_external_thread ON chat_sessions(external_thread_id);
CREATE INDEX idx_chat_messages_session ON chat_messages(chat_session_id);
CREATE INDEX idx_message_attachments_message ON message_attachments(chat_message_id);
```

### 기존 테이블 유지
Note 도메인에서 계속 사용하는 테이블:

```sql
-- 기존 테이블은 그대로 유지
conversations (note_id 기반)
messages (conversation_id 기반)
message_assets
message_tools
```

**결과**: conversations/messages는 Note 전용, chat_sessions/chat_messages는 Chat 전용으로 분리 운영

## 🎯 주요 개선사항

### 1. JSONB 활용
- **유연한 데이터 구조**: content를 JSONB로 저장하여 다양한 메타데이터 지원
- **예시**:
```json
{
  "text": "사용자 질문",
  "latex": "\\int_{0}^{1} x^2 dx",
  "features": ["Solve", "Explain"],
  "mentioned_assets": [1, 2, 3]
}
```

### 2. 사용자 중심 세션 관리
- 노트 종속성 제거
- 사용자별 여러 채팅 세션 관리 가능
- 세션 상태 관리 (active/closed)

### 3. 확장 가능한 첨부 파일
- MessageAttachment 엔티티로 분리
- JSONB metadata로 추가 정보 저장
- 다양한 source 타입 지원

## ⚡ 빌드 & 실행

### 1. 의존성 다운로드
```bash
./gradlew clean build
```

### 2. 데이터베이스 마이그레이션 실행
- JPA `ddl-auto: update` 사용 시 자동 생성
- 또는 위의 SQL 스크립트 직접 실행

### 3. 서버 실행
```bash
./gradlew bootRun
```

## 🧪 테스트

### cURL 테스트
```bash
curl -N -X POST 'http://localhost:8080/api/conversations?isStream=true' \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "테스트 질문",
    "chosenFeatures": ["Solve"]
  }'
```

### 데이터 확인
```sql
-- 세션 확인
SELECT * FROM chat_sessions ORDER BY created_at DESC LIMIT 5;

-- 메시지 확인 (JSONB 파싱)
SELECT 
    chat_message_id,
    role,
    content->>'text' as text,
    content->'features' as features,
    created_at
FROM chat_messages
ORDER BY created_at DESC
LIMIT 10;
```

## 🔍 주의사항

### 1. JSONB 의존성
`build.gradle`에 다음 의존성이 필수:
```gradle
implementation 'io.hypersistence:hypersistence-utils-hibernate-63:3.7.3'
```

### 2. PostgreSQL 버전
JSONB는 PostgreSQL 9.4+ 필요

### 3. 기존 데이터 마이그레이션
**현재 상태**: NoteService는 기존 Conversation/Message 엔티티를 계속 사용합니다.

- ✅ **Note 도메인**: conversations/messages 테이블 사용 (기존 구조 유지)
- ✅ **Chat 도메인**: chat_sessions/chat_messages 테이블 사용 (신규 ERD 구조)

**두 도메인이 공존**하며, 서로 다른 목적으로 사용됩니다:
- Note: 노트 생성 시 초기 대화 생성
- Chat: Proovy-ai 기반 지속적인 채팅 세션

향후 통합 고려 시 별도 마이그레이션 작업
   
2. **Note 도메인도 ChatSession으로 마이그레이션**:
   - NoteService의 createNote 로직을 ChatService와 통합
   - 노트 생성 시 ChatSession 생성

**현재 상태**: 구현은 Chat 도메인만 완료. Note 도메인은 별도 결정 필요.

## ✅ 체크리스트

- [x] 새로운 엔티티 생성 (ChatSession, ChatMessage, MessageAttachment)
- [x] Repository 생성
- [x] Service 레이어 업데이트
- [x] Controller 수정
- [x] JSONB 의존성 추가
- [x] 구 파일 삭제
- [x] 문서 업데이트
- [ ] 데이터베이스 마이그레이션 실행
- [ ] 테스트 실행
- [ ] Note 도메인 영향 확인

## 📞 문제 해결

### 컴파일 에러 발생 시
```bash
# 캐시 정리 후 재빌드
./gradlew clean build --refresh-dependencies
```

### JSONB 타입 인식 오류
- `@Type(JsonBinaryType.class)` 어노테이션 확인
- hypersistence-utils 의존성 확인

### 기존 테이블 충돌
```sql
-- 기존 테이블 완전 삭제
DROP TABLE IF EXISTS conversations CASCADE;
DROP TABLE IF EXISTS messages CASCADE;
```

---

**구현 완료일**: 2026-01-30  
**ERD 기준**: chat_sessions, chat_messages, message_attachments  
**상태**ERD 테이블은 유지
-- chat_sessions/chat_messages만 새로 생성
```

**주의**: 기존 conversations/messages 테이블과 신규 chat_sessions/chat_messages 테이블이 **함께 존재**합니다.

---

**구현 완료일**: 2026-01-30  
**ERD 기준**: chat_sessions, chat_messages, message_attachments (Chat 도메인 전용)  
**기존 유지**: conversations, messages (Note 도메인 전용)