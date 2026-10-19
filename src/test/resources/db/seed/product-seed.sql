-- 재현 가능한 제품 테스트 seed (빈 DB + V1 baseline 위에서 실행).
-- 고정 ID를 사용하고 identity sequence를 seed 이후로 맞춘다. 과거 서비스 row를 흉내 내지 않는다.

INSERT INTO users (user_id, nickname, provider, provider_user_id, email, created_at, updated_at) VALUES
    (1001, 'alice', 'KAKAO', 'kakao-1001', 'alice@example.test', '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
    (1002, 'bob',   'NAVER', 'naver-1002', 'bob@example.test',   '2026-10-01 09:00:00', '2026-10-01 09:00:00');

INSERT INTO user_plans (user_plan_id, user_id, plan_type, started_at, is_active) VALUES
    (1101, 1001, 'FREE', '2026-10-01 09:00:00', TRUE),
    (1102, 1002, 'FREE', '2026-10-01 09:00:00', TRUE);

INSERT INTO credit_balance (id, user_id, daily_free_credit, daily_free_limit, free_credit, paid_credit, created_at, updated_at) VALUES
    (1201, 1001, 100, 100, 50, 0, '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
    (1202, 1002, 100, 100, 0,  0, '2026-10-01 09:00:00', '2026-10-01 09:00:00');

INSERT INTO notes (note_id, user_id, title, created_at, updated_at) VALUES
    (2001, 1001, 'alice note', '2026-10-01 09:10:00', '2026-10-01 09:10:00'),
    (2002, 1002, 'bob note',   '2026-10-01 09:10:00', '2026-10-01 09:10:00'),
    (2003, 1001, 'alice second note', '2026-10-01 09:20:00', '2026-10-01 09:20:00');

INSERT INTO assets (id, user_id, note_id, file_name, file_size, mime_type, object_key, source, status,
                    content_version, sha256, page_count, created_at, updated_at) VALUES
    (3001, 1001, 2001, 'calculus.pdf', 1048576, 'application/pdf', 'users/1001/notes/2001/calculus.pdf', 'upload', 'ACTIVE',
     1, 'a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1', 12, '2026-10-01 09:15:00', '2026-10-01 09:15:00'),
    (3002, 1002, 2002, 'algebra.pdf',  524288,  'application/pdf', 'users/1002/notes/2002/algebra.pdf',  'upload', 'ACTIVE',
     1, 'b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2', 4,  '2026-10-01 09:15:00', '2026-10-01 09:15:00'),
    (3003, 1001, 2001, 'notes.png',    20480,   'image/png',       'users/1001/notes/2001/notes.png',    'upload', 'ACTIVE',
     1, 'c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3', NULL, '2026-10-01 09:16:00', '2026-10-01 09:16:00');

SELECT setval(pg_get_serial_sequence('users', 'user_id'), 10000);
SELECT setval(pg_get_serial_sequence('user_plans', 'user_plan_id'), 10000);
SELECT setval(pg_get_serial_sequence('credit_balance', 'id'), 10000);
SELECT setval(pg_get_serial_sequence('notes', 'note_id'), 10000);
SELECT setval(pg_get_serial_sequence('assets', 'id'), 10000);
