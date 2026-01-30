# Conversation API 테스트 가이드

## 📝 API 테스트 방법

### 1. 환경 설정 확인

#### .env 파일
```properties
PROOVY_AI_HOST=http://localhost:8081
```

#### Proovy-ai 서버 실행
```bash
cd c:\Users\shcks\Desktop\Proovy\Proovy-ai
# FastAPI 서버를 8081 포트로 실행
```

### 2. 데이터베이스 마이그레이션

새로운 ERD 기준에 맞는 테이블 생성이 필요합니다:

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
```

또는 JPA의 `ddl-auto: update` 설정이 활성화되어 있다면 자동으로 적용됩니다.

### 3. SSE 스트리밍 테스트

#### Postman 설정
1. POST 요청 생성: `http://localhost:8080/api/conversations?isStream=true`
2. Headers:
   - `Authorization: Bearer {JWT_TOKEN}`
   - `Content-Type: application/json`
3. Body (JSON):
```json
{
  "text": "이 문제를 풀어줘",
  "latex": "\\int_{0}^{1} x^2 dx",
  "mentionedAssetIds": [1, 2],
  "chosenFeatures": ["Solve", "Explain"]
}
```

#### cURL 테스트
```bash
curl -N -X POST 'http://localhost:8080/api/conversations?isStream=true' \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "이 문제를 풀어줘",
    "latex": "\\int_{0}^{1} x^2 dx",
    "mentionedAssetIds": [1, 2],
    "chosenFeatures": ["Solve"]
  }'
```

> 📌 `-N` 옵션은 SSE 버퍼링을 비활성화합니다.

#### JavaScript (EventSource)
```javascript
const token = 'YOUR_JWT_TOKEN';

const eventSource = new EventSource(
  `http://localhost:8080/api/conversations?isStream=true`,
  {
    headers: {
      'Authorization': `Bearer ${token}`
    }
  }
);

eventSource.addEventListener('message', (event) => {
  const data = JSON.parse(event.data);
  console.log('Message:', data);
});

eventSource.addEventListener('metadata', (event) => {
  const data = JSON.parse(event.data);
  console.log('Metadata:', data);
});

eventSource.addEventListener('[DONE]', (event) => {
  console.log('Stream completed');
  eventSource.close();
});

eventSource.addEventListener('error', (event) => {
  console.error('Error:', event);
  eventSource.close();
});
```

### 4. 예상 응답 흐름

#### 1단계: 메시지 스트리밍
```
event: message
data: {"content":"적분을","type":"text"}

event: message
data: {"content":" 계산하면","type":"text"}
```

#### 2단계: 메타데이터 수신
```
event: metadata
data: {"thread_id":"thread_abc123"}
```

#### 3단계: 스트림 종료
```
event: [DONE]
data: 
```

### 5. 데이터베이스 확인

#### ChatSession 조회
```sql
SELECT * FROM chat_sessions WHERE user_id = {userId};
```

결과: `external_thread_id`에 Proovy-ai의 thread_id가 저장되어 있어야 함

#### ChatMessage 조회
```sql
SELECT * FROM chat_messages 
WHERE chat_session_id = {sessionId}
ORDER BY created_at DESC
LIMIT 10;
```

결과:
- USER 역할 메시지: content JSONB에 사용자 입력 데이터
- ASSISTANT 역할 메시지: content JSONB에 AI 응답 (누적된 전체 내용)

#### Content JSONB 구조 확인
```sql
SELECT 사용자
잘못된 JWT 토큰 사용
예상 응답: `401 UNAUTHORIZED` 또는 `404 NOT_FOUND`, `USER4041`

#### 2
### 6. 에러 케이스 테스트

#### 1) 존재하지 않는 노트
```bash
POST /api/conversations/999999?isStream=true
```
예상 응답: `404 NOT_FOUND`, `CONV4041`

#### 2) 권한 없는 노트
다른 사용자의 노트 ID 사용
예상 응답: `403 FORBIDDEN`, `NOTE4031`

#### 3) 잘못된 기능
```json
{
  "text": "테스트",
  "chosenFeatures": ["InvalidFeature"]
}
```
예상 응답: `400 BAD_REQUEST`, `CONV4001`

#### 4) Proovy-ai 서버 미실행
Proovy-ai 서버를 종료한 상태에서 요청
예상 응답: `500 INTERNAL_SERVER_ERROR`, `CONV5002`

### 7. 로그 확인

#### Spring 애플리케이션 로그
```
INFO  - Create conversation - noteId: 1, userId: 123, isStream: true
INFO  - Calling Proovy-ai stream: http://localhost:8081/stream
DEBUG - Received event: message
DEBUG - Received event: metadata
INFO  - Streaming completed for conversation: 1, message: 10
INFO  - SSE stream completed for noteId: 1
```userId: 123, isStream: true
INFO  - Calling Proovy-ai stream: http://localhost:8081/stream
DEBUG - Received event: message
DEBUG - Received event: metadata
INFO  - Streaming completed for session: 1, message: 10
INFO  - SSE stream completed for userId: 123

#### 동시 요청 테스트
```bash
# Apache Bench 사용 예시
ab -n 10 -c 2 -T 'application/json' \
  -H "Authorization: Bearer TOKEN" \
  -p request.json \
  'http://localhost:8080/api/conversations?isStream=true'
```

#### 타임아웃 테스트
Proovy-ai 응답을 의도적으로 지연시켜 5분 타임아웃 동작 확인

### 9. Swagger UI 테스트

브라우저에서 접속:
```
http://localhost:8080/swagger-ui/index.html
```

1. `Conversation` 태그 확장
2. `POST /api/conversations` 엔드포인트 선택
3. "Try it out" 클릭
4. 파라미터 입력 후 "Execute"

> ⚠️ Swagger UI는 SSE 스트리밍을 완벽하게 표시하지 못할 수 있습니다. 실제 테스트는 cURL이나 Postman 권장.

## 🐛 트러블슈팅

### 1. SSE 연결이 즉시 끊김
- **원인**: 방화벽 또는 프록시 설정
- **해결**: 로컬 환경에서 직접 테스트

### 2. thread_id가 저장되지 않음
- **원인**: Proovy-ai가 metadata 이벤트를 보내지 않음
- **확인**: Proovy-ai 로그 및 응답 형식 검증

### 3. 메시지가 중복 저장됨
- **원인**: 트랜잭션 경계 문제
- **확인**: 서비스 메서드의 `@Transactional` 설정

### 4. 자산 URL 변환 실패
- **원인**: mentionedAssetIds에 존재하지 않는 ID 포함
- **확인**: Asset 테이블과 권한 확인

### 5. 타임아웃 발생
- **원인**: Proovy-ai 응답이 5분 초과
- **해결**: `application.yaml`에서 타임아웃 조정 또는 Proovy-ai 성능 개선

### 6. JSONB 데이터 조회 오류
- **원인**: hypersistence-utils 의존성 누락
- **확인**: build.gradle에 `io.hypersistence:hypersistence-utils-hibernate-63` 추가 확인

## 📊 모니터링 포인트

1. **세션 수**: 활성 세션 vs 종료 세션 비율
6. **JSONB 크기**: ChatMessage content 평균 크기

## 🔍 추가 검증 사항

- [ ] 같은 사용자에 대한 연속 요청 시 thread_id 재사용 확인
- [ ] 스트리밍 중 연결 끊김 시 AI 메시지 삭제 확인
- [ ] 여러 자산 첨부 시 모든 S3 URL 전달 확인
- [ ] LaTeX 수식이 JSONB에 정확히 포함되는지 확인
- [ ] 지원되는 모든 기능(Solve, Check, Explain, Variant, Practice) 테스트
- [ ] JSONB content 필드의 다양한 구조 저장 및 조회 테스트
- [ ] 세션 status 변경 (active ↔ closed
- [ ] 스트리밍 중 연결 끊김 시 AI 메시지 삭제 확인
- [ ] 여러 자산 첨부 시 모든 S3 URL 전달 확인
- [ ] LaTeX 수식이 metadata에 정확히 포함되는지 확인
- [ ] 지원되는 모든 기능(Solve, Check, Explain, Variant, Practice) 테스트
