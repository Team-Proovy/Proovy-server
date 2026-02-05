-- =============================================
-- Storage API 테스트용 데이터
-- =============================================

-- 1. 테스트 유저 생성
INSERT INTO users (nickname, email, provider, provider_user_id, created_at, updated_at)
VALUES ('테스트유저', 'test@test.com', 'KAKAO', 'test123', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 2. 테스트 노트 생성
INSERT INTO notes (user_id, title, created_at, updated_at)
VALUES
  (1, '이산수학 과제', NOW(), NOW()),
  (1, '선형대수 정리', NOW(), NOW()),
  (1, '알고리즘 스터디', NOW(), NOW());

-- 3. 테스트 자산(파일) 생성
-- file_size는 bytes 단위 (1MB = 1048576 bytes)
INSERT INTO assets (user_id, note_id, file_name, file_size, mime_type, s3key, source, status, created_at, updated_at, version)
VALUES
  -- 노트1: 이산수학 과제 (총 240MB)
  (1, 1, 'exercise_of_RREF.pdf', 83886080, 'application/pdf', 'users/1/assets/exercise1.pdf', 'upload', 'UPLOADED', NOW(), NOW(), 0),
  (1, 1, 'solution.py', 52428800, 'text/x-python', 'users/1/assets/solution.py', 'upload', 'UPLOADED', NOW(), NOW(), 0),
  (1, 1, 'graph_diagram.png', 104857600, 'image/png', 'users/1/assets/diagram.png', 'upload', 'UPLOADED', NOW(), NOW(), 0),

  -- 노트2: 선형대수 정리 (총 150MB)
  (1, 2, 'matrix_operations.pdf', 78643200, 'application/pdf', 'users/1/assets/matrix.pdf', 'ai_generated', 'UPLOADED', NOW(), NOW(), 0),
  (1, 2, 'eigenvalue_notes.pdf', 78643200, 'application/pdf', 'users/1/assets/notes.pdf', 'upload', 'UPLOADED', NOW(), NOW(), 0),

  -- 노트3: 알고리즘 스터디 (총 100MB)
  (1, 3, 'sorting_algorithms.java', 52428800, 'text/x-java', 'users/1/assets/sorting.java', 'upload', 'UPLOADED', NOW(), NOW(), 0),
  (1, 3, 'complexity_chart.png', 52428800, 'image/png', 'users/1/assets/chart.png', 'upload', 'UPLOADED', NOW(), NOW(), 0);
