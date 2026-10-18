package com.proovy.domain.run.terminal;

/**
 * terminal 수락 거부 범주. 1.82는 범주만 기록·로그하고 payload 값은 남기지 않는다.
 * 앞쪽은 Brain terminal-v2 계약과 같은 구조 규칙, {@code SOURCE_SCOPE} 이후는 Server가 소유한 권한·metadata 규칙이다.
 */
public enum TerminalRejectionReason {
    /** completed·failed가 아닌 event(activity·저장 전용 run.canceled). */
    NOT_PUBLICATION_TERMINAL,
    /** closed model 위반: 알 수 없는 필드·누락·타입·길이·형식·enum. */
    MALFORMED,
    /** SafeMarkdown 허용 목록 위반. */
    UNSAFE_MARKDOWN,
    /** 공개 page·block·ID·anchor·marker·source catalog 정합 위반. */
    RESPONSE_INTEGRITY,
    /** substantive·clarification_only·unsupported_only 허용 구조 위반. */
    DISPOSITION_STRUCTURE,
    /** 배지·step mark·verification summary·Verified Solution artifact·coverage·공개 허용 문구 정합 위반. */
    VERIFICATION_INTEGRITY,
    /** source label·artifact source ID가 catalog와 맞지 않는다. */
    SOURCE_CATALOG,
    /** selected page provenance가 인용 범위와 맞지 않는다(누락·범위 밖·중복·version 불일치). */
    PROVENANCE,
    /** 고정 안전 문구가 아닌 실패 payload(code·class·message·reason 불일치). */
    FAILURE_PAYLOAD,
    /** 공개 텍스트에 signed URL·grant 흔적이 있다. */
    FORBIDDEN_CONTENT,
    /** 다른 run·attempt·fence의 terminal. */
    RUN_MISMATCH,
    /** source asset이 이 run의 승인 dependency·user/Note·ACTIVE·version·page 범위 밖이다. */
    SOURCE_SCOPE,
    /** 기존 Verified Solution 참조가 run이 승인한 참조와 맞지 않는다. */
    SOLUTION_REFERENCE_SCOPE,
    /** revises_solution_id가 승인 범위 밖·superseded·중복 정정·순환이다. */
    REVISION
}
