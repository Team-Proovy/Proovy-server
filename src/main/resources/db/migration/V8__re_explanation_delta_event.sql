-- V8: Task 2.14 재설명 실시간 본문 스트리밍(re_explanation.delta) event 저장 허용

ALTER TABLE ai_run_events DROP CONSTRAINT ck_ai_run_events_type;
ALTER TABLE ai_run_events ADD CONSTRAINT ck_ai_run_events_type CHECK (event_type IN (
    'run.created', 'run.canceling', 'run.canceled', 'run.failed', 'run.completed',
    'activity.started', 'activity.updated', 'activity.completed', 'activity.failed', 'activity.canceled',
    'response.page.started', 'response.page.completed',
    're_explanation.delta'
));

ALTER TABLE ai_run_events ADD CONSTRAINT ck_ai_run_events_re_explanation_delta CHECK (
    event_type <> 're_explanation.delta'
    OR (
        source_event_id IS NOT NULL
        AND attempt IS NOT NULL
        AND activity_id IS NULL
        AND jsonb_typeof(payload -> 'deltaIndex') = 'number'
        AND (payload ->> 'deltaIndex') ~ '^(0|[1-9][0-9]*)$'
        AND jsonb_typeof(payload -> 'delta') = 'string'
        AND octet_length(payload ->> 'delta') > 0
    )
);
