"""Regenerate the internal-run-v1 golden fixtures (task 1.43).

Deterministic builder — ``python build_fixtures.py`` rewrites every pinned
file; tests call the ``build_*`` functions through ``runpy`` to prove the
checked-in fixtures still match the contract implementation.
"""

from __future__ import annotations

import json
from copy import deepcopy
from pathlib import Path
from typing import Any

from proovy_brain.contracts.internal_run import (
    RunDecision,
    RunOperation,
    StreamRejectReason,
    contention_decisions,
    parse_internal_run_request,
    to_internal_run_payload,
)
from proovy_brain.contracts.internal_run_fingerprint import (
    compute_request_fingerprint,
    request_fingerprint_canonical,
)

SECRET_MARKER = "DO-NOT-LEAK-raw-payload"
"""Embedded in negative fixtures so leak tests assert against real input."""

ROOT = Path(__file__).resolve().parent

USAGE = {
    "used_llm_calls": 0,
    "used_tool_calls": 0,
    "used_output_tokens": 0,
    "used_cost_micros": 0,
    "used_page_understandings": 0,
}

LIMITS = {
    "max_llm_calls": 40,
    "max_tool_calls": 40,
    "max_output_tokens": 100000,
    "max_cost_micros": 5000000,
    "max_page_understandings": 5,
    "max_problems": 10,
    "max_claims": 50,
}

FULL = {
    "contract_version": "internal-run-v1",
    "request_id": "req-0182",
    "run_id": "run-441",
    "user_id": "user-42",
    "note_id": "note-77",
    "conversation_id": "note-77",
    "attempt": 1,
    "fence_token": "fence-a1b2",
    "input": {
        "text": "첨부한 풀이를 검산해줘",
        "latex": "\\int_0^1 x^2 \\, dx",
        "requested_tasks": ["solve", "check"],
        "requested_outputs": ["text"],
        "locale": "ko-KR",
    },
    "history": [
        {"message_id": "msg-101", "role": "user", "text": "이전 질문"},
        {"message_id": "msg-102", "role": "assistant", "text": "이전 답변"},
    ],
    "verified_solution_refs": [
        {
            "solution_id": "solution-7",
            "source_message_id": "msg-102",
            "status": "active",
            "revises_solution_id": "solution-3",
            "problem_summary": "이차방정식의 근",
            "claims": [
                {
                    "claim_id": "claim-1",
                    "statement": "x^2-5x+6=0의 근은 2와 3이다",
                    "coverage": "direct",
                },
                {
                    "claim_id": "claim-2",
                    "statement": "근의 합은 5이다",
                    "coverage": "supporting",
                },
            ],
        }
    ],
    "turn_attachment_refs": [
        {
            "attachment_id": "att-88",
            "source_message_id": "msg-101",
            "sha256": "ab" * 32,
            "mime": "image/png",
            "page_understanding_version": "pu-v2",
            "image_normalization_version": "norm-v1",
            "display_label": "연습지 사진",
        }
    ],
    "viewer_context": {
        "asset_id": "asset-55",
        "sha256": "cd" * 32,
        "mime": "application/pdf",
        "byte_size": 2048576,
        "page_count": 12,
        "upload_policy_version": "up-v1",
        "page_understanding_version": "pu-v2",
        "image_normalization_version": "norm-v1",
        "page": 3,
        "selection": {
            "left": 0.125,
            "top": 0.25,
            "right": 0.875,
            "bottom": 0.5,
        },
        "read_url": "https://storage.googleapis.com/bucket/asset-55?sig=abc",
    },
    "retrieval_scope": {
        "note_id": "note-77",
        "corpus_version": "corpus-9",
        "search_extraction_version": "se-v3",
        "chunking_version": "chunk-v2",
        "embedding_version": "emb-v4",
    },
    "turn_attachments": [
        {
            "attachment_id": "att-201",
            "sha256": "ef" * 32,
            "mime": "image/jpeg",
            "byte_size": 524288,
            "pixel_width": 1600,
            "pixel_height": 1200,
            "pixel_count": 1920000,
            "upload_policy_version": "up-v1",
            "page_understanding_version": "pu-v2",
            "image_normalization_version": "norm-v1",
            "read_url": "https://storage.googleapis.com/bucket/att-201?sig=def",
        }
    ],
    "asset_access_grant": "grant-opaque-token",
    "execution_options": {
        "budget_policy_version": "budget-v1",
        "request_deadline_at": "2026-10-02T09:30:00Z",
        "limits": dict(LIMITS),
        "usage": dict(USAGE),
    },
}

MINIMAL = {
    "contract_version": "internal-run-v1",
    "request_id": "req-0180",
    "run_id": "run-440",
    "user_id": "user-42",
    "note_id": "note-77",
    "conversation_id": "note-77",
    "attempt": 1,
    "fence_token": "fence-a1b1",
    "input": {
        "text": "x^2 - 5x + 6 = 0을 풀어줘",
        "requested_tasks": ["solve"],
        "requested_outputs": ["text"],
        "locale": "ko-KR",
    },
    "history": [],
    "verified_solution_refs": [],
    "turn_attachment_refs": [],
    "retrieval_scope": {
        "note_id": "note-77",
        "corpus_version": "corpus-9",
        "search_extraction_version": "se-v3",
        "chunking_version": "chunk-v2",
        "embedding_version": "emb-v4",
    },
    "turn_attachments": [],
    "execution_options": {
        "budget_policy_version": "budget-v1",
        "request_deadline_at": "2026-10-02T09:00:00Z",
        "limits": dict(LIMITS),
        "usage": dict(USAGE),
    },
}


def _wire(raw: dict) -> dict:
    """Normalize through the contract — what goes into a fixture is
    exactly what the wire carries."""

    return to_internal_run_payload(parse_internal_run_request(raw))


def _mutate(*fns: Any) -> dict:
    raw = deepcopy(FULL)
    for fn in fns:
        fn(raw)
    return raw


def build_requests() -> dict[str, dict]:
    return {
        "full": _wire(FULL),
        "minimal": _wire(MINIMAL),
    }


def _fp_case(
    name: str,
    relation: str,
    raw: dict,
    rationale: str,
    *,
    store_raw: bool = False,
) -> dict:
    request = parse_internal_run_request(raw)
    return {
        "name": name,
        "relation": relation,
        "rationale": rationale,
        # ``store_raw`` keeps the pre-normalization wire bytes so a Java
        # consumer replays parse→normalize→project→hash itself.
        "request": raw if store_raw else _wire(raw),
        "expected_canonical": request_fingerprint_canonical(request),
        "expected_fingerprint": compute_request_fingerprint(request),
    }


def build_fingerprint_cases() -> dict[str, dict]:
    return {
        "base": _fp_case(
            "base",
            "same",
            FULL,
            "기준 요청 — 나머지 사례가 이 fingerprint 와 비교된다.",
        ),
        "retry-attempt": _fp_case(
            "retry-attempt",
            "same",
            _mutate(lambda r: r.update({"attempt": 2})),
            "네트워크 재시도는 attempt 를 올리지만 의미는 그대로다.",
        ),
        "retry-fence": _fp_case(
            "retry-fence",
            "same",
            _mutate(lambda r: r.update({"fence_token": "fence-c3d4"})),
            "fence 회전은 운영 스탬프일 뿐 의미를 바꾸지 않는다.",
        ),
        "retry-urls": _fp_case(
            "retry-urls",
            "same",
            _mutate(
                lambda r: r["viewer_context"].update(
                    {
                        "read_url": (
                            "https://storage.googleapis.com/bucket/asset-55?sig=zzz"
                        )
                    }
                ),
                lambda r: r["turn_attachments"][0].update(
                    {
                        "read_url": (
                            "https://storage.googleapis.com/bucket/att-201?sig=zzz"
                        )
                    }
                ),
            ),
            "서명 read URL 갱신은 의미에 영향이 없다.",
        ),
        "retry-grant": _fp_case(
            "retry-grant",
            "same",
            _mutate(lambda r: r.update({"asset_access_grant": "grant-rotated-token"})),
            "Asset Access Grant 는 실행 자격증명이지 의미 입력이 아니다.",
        ),
        "retry-deadline-usage": _fp_case(
            "retry-deadline-usage",
            "same",
            _mutate(
                lambda r: r["execution_options"].update(
                    {"request_deadline_at": "2026-10-02T10:30:00Z"}
                ),
                lambda r: r["execution_options"]["usage"].update(
                    {"used_llm_calls": 5, "used_cost_micros": 900000}
                ),
            ),
            "절대 deadline 과 누적 사용 counter 는 운영 값이다.",
        ),
        "retry-request-ids": _fp_case(
            "retry-request-ids",
            "same",
            _mutate(
                lambda r: r.update({"request_id": "req-9999", "run_id": "run-999"})
            ),
            "request_id/run_id 는 식별 스탬프다 — 의미 지문에는 들어가지 않는다.",
        ),
        "lifecycle-status": _fp_case(
            "lifecycle-status",
            "same",
            _mutate(
                lambda r: r["verified_solution_refs"][0].update(
                    {"status": "superseded", "revises_solution_id": None}
                )
            ),
            "superseded/revises 같은 수명주기 메타데이터는 지문 밖이다.",
        ),
        "order-collections": _fp_case(
            "order-collections",
            "same",
            _mutate(
                lambda r: r["input"].update(
                    {"requested_tasks": list(reversed(r["input"]["requested_tasks"]))}
                ),
                lambda r: r["verified_solution_refs"][0].update(
                    {"claims": list(reversed(r["verified_solution_refs"][0]["claims"]))}
                ),
            ),
            "비순서 의미의 컬렉션(tasks·claims·refs)은 canonical 정렬된다.",
        ),
        "derived-asset-facts": _fp_case(
            "derived-asset-facts",
            "same",
            _mutate(
                lambda r: r["viewer_context"].update(
                    {"byte_size": 1, "upload_policy_version": "up-v9"}
                ),
                lambda r: r["turn_attachments"][0].update(
                    {
                        "byte_size": 1,
                        "image_normalization_version": "norm-v9",
                        "upload_policy_version": "up-v9",
                    }
                ),
                lambda r: r["turn_attachment_refs"][0].update(
                    {
                        "mime": "image/jpeg",
                        "image_normalization_version": "norm-v9",
                        "display_label": "새 라벨",
                    }
                ),
            ),
            "sha256으로 이미 고정되는 바이트/표시·재파생 버전은 지문 밖이다.",
        ),
        "diff-text": _fp_case(
            "diff-text",
            "different",
            _mutate(lambda r: r["input"].update({"text": "다른 문제를 풀어줘"})),
            "입력 텍스트가 달라지면 새로 계산한다.",
        ),
        "diff-latex": _fp_case(
            "diff-latex",
            "different",
            _mutate(lambda r: r["input"].update({"latex": "e^{i\\pi}=-1"})),
            "latex 가 달라지면 새로 계산한다.",
        ),
        "diff-locale": _fp_case(
            "diff-locale",
            "different",
            _mutate(lambda r: r["input"].update({"locale": "en-US"})),
            "locale 은 의미 입력이다.",
        ),
        "diff-requested-tasks": _fp_case(
            "diff-requested-tasks",
            "different",
            _mutate(lambda r: r["input"].update({"requested_tasks": ["solve"]})),
            "요청 task 집합이 달라지면 새로 계산한다.",
        ),
        "diff-history": _fp_case(
            "diff-history",
            "different",
            _mutate(lambda r: r["history"][0].update({"text": "수정된 이전 질문"})),
            "대화 이력의 내용이 달라지면 새로 계산한다.",
        ),
        "diff-history-order": _fp_case(
            "diff-history-order",
            "different",
            _mutate(lambda r: r["history"].reverse()),
            "history 는 시간 순서가 의미다 — 순서가 바뀌면 새 지문.",
        ),
        "diff-solution-content": _fp_case(
            "diff-solution-content",
            "different",
            _mutate(
                lambda r: r["verified_solution_refs"][0]["claims"][0].update(
                    {"statement": "근은 1과 6이다"}
                )
            ),
            "Verified Solution 의 의미 내용(summary+claims)이 달라지면 새로 계산한다.",
        ),
        "diff-solution-id": _fp_case(
            "diff-solution-id",
            "different",
            _mutate(
                lambda r: r["verified_solution_refs"][0].update(
                    {"solution_id": "solution-8"}
                )
            ),
            "다른 solution 선택은 의미가 다르다.",
        ),
        "diff-attachment-ref": _fp_case(
            "diff-attachment-ref",
            "different",
            _mutate(
                lambda r: r["turn_attachment_refs"][0].update(
                    {"page_understanding_version": "pu-v3"}
                )
            ),
            "첨부 참조의 Page Understanding version 이 달라지면 새로 계산한다.",
        ),
        "diff-viewer-page": _fp_case(
            "diff-viewer-page",
            "different",
            _mutate(lambda r: r["viewer_context"].update({"page": 4})),
            "사용자가 다른 page 를 가리키면 의미가 다르다.",
        ),
        "diff-viewer-selection": _fp_case(
            "diff-viewer-selection",
            "different",
            _mutate(
                lambda r: r["viewer_context"].update(
                    {
                        "selection": {
                            "left": 0.0,
                            "top": 0.0,
                            "right": 0.5,
                            "bottom": 0.5,
                        }
                    }
                )
            ),
            "Viewer Focus 의 선택 영역이 달라지면 새로 계산한다.",
        ),
        "diff-corpus-version": _fp_case(
            "diff-corpus-version",
            "different",
            _mutate(
                lambda r: r["retrieval_scope"].update({"corpus_version": "corpus-10"})
            ),
            "검색 corpus snapshot 이 달라지면 새로 계산한다.",
        ),
        "diff-extraction-version": _fp_case(
            "diff-extraction-version",
            "different",
            _mutate(
                lambda r: r["retrieval_scope"].update(
                    {"search_extraction_version": "se-v4"}
                )
            ),
            "Search Extraction version 은 의미 입력이다.",
        ),
        "diff-turn-attachment-sha": _fp_case(
            "diff-turn-attachment-sha",
            "different",
            _mutate(lambda r: r["turn_attachments"][0].update({"sha256": "0" * 64})),
            "첨부 bytes 가 달라지면 새로 계산한다.",
        ),
        "diff-limits": _fp_case(
            "diff-limits",
            "different",
            _mutate(
                lambda r: r["execution_options"]["limits"].update({"max_llm_calls": 41})
            ),
            "고정 정책 한도 snapshot 의 일부가 달라지면 새로 계산한다.",
        ),
        "diff-budget-policy": _fp_case(
            "diff-budget-policy",
            "different",
            _mutate(
                lambda r: r["execution_options"].update(
                    {"budget_policy_version": "budget-v2"}
                )
            ),
            "budget policy version 은 의미 입력이다.",
        ),
        "normalize-whitespace": _fp_case(
            "normalize-whitespace",
            "same",
            _mutate(
                lambda r: r["input"].update(
                    {"text": "\u00a0첨부한 풀이를 검산해줘\u3000"}
                ),
                lambda r: r["history"][0].update({"text": "\u3000이전 질문\u00a0"}),
                lambda r: r["verified_solution_refs"][0].update(
                    {"problem_summary": "\u00a0이차방정식의 근\u3000"}
                ),
                lambda r: r["verified_solution_refs"][0]["claims"][0].update(
                    {"statement": "\u00a0x^2-5x+6=0의 근은 2와 3이다\u3000"}
                ),
                lambda r: r["turn_attachment_refs"][0].update(
                    {"display_label": "\u00a0연습지 사진\u3000"}
                ),
            ),
            "양 끝의 Unicode whitespace(U+00A0·U+3000)는 파싱 정규화에서 "
            "제거된 뒤 지문에 들어간다 — request 는 정규화 전 wire 값을 "
            "보존한다.",
            store_raw=True,
        ),
    }


def build_operations() -> dict[str, dict]:
    base_fp = compute_request_fingerprint(parse_internal_run_request(FULL))
    status_query = {
        "contract_version": "internal-run-v1",
        "run_id": "run-441",
        "attempt": 2,
        "fence_token": "fence-c3d4",
    }
    cancel_request = {
        "contract_version": "internal-run-v1",
        "run_id": "run-441",
        "attempt": 1,
        "fence_token": "fence-a1b2",
        "cause": "user_requested",
    }
    cleanup_request = {
        "contract_version": "internal-run-v1",
        "run_id": "run-441",
    }
    return {
        "status-executing": {
            "name": "status-executing",
            "kind": "status",
            "rationale": "실행 중 레코드를 조회 — 조회는 실행을 만들지 않는다.",
            "request": status_query,
            "response": {
                "contract_version": "internal-run-v1",
                "run_id": "run-441",
                "state": "executing",
                "attempt": 2,
                "fence_token": "fence-c3d4",
                "request_fingerprint": base_fp,
            },
        },
        "status-terminal": {
            "name": "status-terminal",
            "kind": "status",
            "rationale": ("terminal 레코드 조회 — 저장된 run.completed 를 재전달한다."),
            "request": status_query,
            "response": {
                "contract_version": "internal-run-v1",
                "run_id": "run-441",
                "state": "terminal",
                "attempt": 1,
                "fence_token": "fence-a1b2",
                "request_fingerprint": base_fp,
                "terminal": {
                    "contract_version": "brain-source-v1",
                    "run_id": "run-441",
                    "attempt": 1,
                    "fence_token": "fence-a1b2",
                    "source_event_id": "run-441:terminal:r0:9f8c4d2e",
                    "occurred_at": "2026-10-02T09:31:00Z",
                    "type": "run.completed",
                    "payload": {
                        "response_disposition": "clarification_only",
                        "clarification_reason": "ambiguous_target",
                        "assistant_response": {
                            "schema_version": "assistant-response-v1",
                            "response_pages": [
                                {
                                    "response_page_id": "page-1",
                                    "presentation": "main",
                                    "result_blocks": [
                                        {
                                            "block_id": "block-1",
                                            "body": (
                                                "어떤 문제의 풀이를 원하는지 "
                                                "알려 주세요."
                                            ),
                                            "kind": "rich_text",
                                        }
                                    ],
                                }
                            ],
                            "source_references": [],
                        },
                        "verified_solutions": [],
                        "selected_page_provenance": [],
                        "model": "fake/main-model",
                        "tier": "main",
                        "verification_summary": [],
                        "source_labels": [],
                    },
                },
            },
        },
        "status-absent": {
            "name": "status-absent",
            "kind": "status",
            "rationale": "레코드 없음 — 조회만으로 실행이 시작되지 않는다.",
            "request": status_query,
            "response": {
                "contract_version": "internal-run-v1",
                "run_id": "run-441",
                "state": "absent",
            },
        },
        "status-terminal-canceled": {
            "name": "status-terminal-canceled",
            "kind": "status",
            "rationale": (
                "취소된 run 의 terminal 조회 — 저장된 run.canceled 를 "
                "재전달한다. 저장 전용 variant 이므로 brain-source-v1 "
                "SourceEvent union 에는 들어가지 않는다 (task 1.47, "
                "internal-run-v1 additive 확장)."
            ),
            "request": status_query,
            "response": {
                "contract_version": "internal-run-v1",
                "run_id": "run-441",
                "state": "terminal",
                "attempt": 1,
                "fence_token": "fence-a1b2",
                "request_fingerprint": base_fp,
                "terminal": {
                    "contract_version": "brain-source-v1",
                    "run_id": "run-441",
                    "attempt": 1,
                    "fence_token": "fence-a1b2",
                    "source_event_id": "run-441:terminal:r0:9f8c4d2e",
                    "occurred_at": "2026-10-02T09:29:00Z",
                    "type": "run.canceled",
                    "payload": {"cause": "user_requested"},
                },
            },
        },
        "cancel-accepted": {
            "name": "cancel-accepted",
            "kind": "cancel",
            "rationale": "실행 중 레코드에 cancel 수락 — CANCELING 전환.",
            "request": cancel_request,
            "response": {
                "contract_version": "internal-run-v1",
                "run_id": "run-441",
                "outcome": "accepted",
                "state": "canceling",
            },
        },
        "cancel-unknown": {
            "name": "cancel-unknown",
            "kind": "cancel",
            "rationale": "모르는 run 의 cancel — 멱등 거절.",
            "request": cancel_request,
            "response": {
                "contract_version": "internal-run-v1",
                "run_id": "run-441",
                "outcome": "unknown_run",
                "state": "absent",
            },
        },
        "cancel-already-canceling": {
            "name": "cancel-already-canceling",
            "kind": "cancel",
            "rationale": ("이미 CANCELING 인 run 의 재전달된 cancel — 멱등 ack."),
            "request": cancel_request,
            "response": {
                "contract_version": "internal-run-v1",
                "run_id": "run-441",
                "outcome": "already_canceling",
                "state": "canceling",
            },
        },
        "cancel-already-terminal": {
            "name": "cancel-already-terminal",
            "kind": "cancel",
            "rationale": ("terminal 저장이 끝난 run 의 늦은 cancel — 거절."),
            "request": cancel_request,
            "response": {
                "contract_version": "internal-run-v1",
                "run_id": "run-441",
                "outcome": "already_terminal",
                "state": "terminal",
            },
        },
        "cleanup-cleaned": {
            "name": "cleanup-cleaned",
            "kind": "cleanup",
            "rationale": "terminal 저장 확인 뒤 runtime 레코드 제거 — 멱등.",
            "request": cleanup_request,
            "response": {
                "contract_version": "internal-run-v1",
                "run_id": "run-441",
                "outcome": "cleaned",
                "state": "terminal",
            },
        },
        "cleanup-noop": {
            "name": "cleanup-noop",
            "kind": "cleanup",
            "rationale": "레코드가 없으면 cleanup 은 아무것도 하지 않는다.",
            "request": cleanup_request,
            "response": {
                "contract_version": "internal-run-v1",
                "run_id": "run-441",
                "outcome": "noop",
                "state": "absent",
            },
        },
        "cleanup-protected": {
            "name": "cleanup-protected",
            "kind": "cleanup",
            "rationale": "활성/CANCELING 실행은 cleanup 이 건드리지 않는다.",
            "request": cleanup_request,
            "response": {
                "contract_version": "internal-run-v1",
                "run_id": "run-441",
                "outcome": "protected",
                "state": "executing",
            },
        },
        "stream-request-id-bound": {
            "name": "stream-request-id-bound",
            "kind": "stream",
            "rationale": (
                "request_id 가 다른 run_id 에 이미 묶인 stream 전달은 항상 "
                "호출자 버그다 — 한 request 는 정확히 하나의 run 에 대응하고 "
                "재시도는 같은 run_id 의 새 attempt 이다 (task 1.65, "
                "docs/plan/05). 기존 run 에 붙지 않고 즉시 409로 거부되며 "
                "새 기록을 만들지 않는다. tombstone 만 남은 정리된 run 의 "
                "request_id 도 같은 규칙이다."
            ),
            # FULL 과 같은 request_id(req-0182)를 다른 run_id 로 전달한다.
            "request": _wire(_mutate(lambda r: r.update({"run_id": "run-999"}))),
            "bound_run_id": "run-441",
            "http_status": 409,
            "response": {
                "error": "conflict",
                "reason": StreamRejectReason.REQUEST_ID_BOUND_TO_OTHER_RUN.value,
                "existing_run_id": "run-441",
            },
        },
    }


def build_decisions() -> dict:
    """The full operation × record-state decision matrix — the shared
    golden a Java consumer checks its own table against."""

    return {
        "version": "run-decisions-v2",
        "rationale": (
            "decide_run_request 의 4×4×2 전수 결정표 — operation/record "
            "state 값은 wire 문자열, fingerprint_match 는 STREAM 의 의미 "
            "일치 여부다. v2는 binding_overrides 섹션을 추가한다 (task 1.65): "
            "identity 충돌은 contention 표보다 먼저 평가된다."
        ),
        "decisions": [
            {
                "operation": operation.value,
                "record_state": state.value,
                "fingerprint_match": match,
                "decision": decision.value,
            }
            for (operation, state, match), decision in sorted(
                contention_decisions().items(),
                key=lambda item: (
                    item[0][0].value,
                    item[0][1].value,
                    item[0][2],
                ),
            )
        ],
        # Identity bindings outrank the contention table: they are checked
        # on the (run_id, request_id) pair before record_state or the
        # fingerprint are ever compared.
        "binding_overrides": [
            {
                "operation": RunOperation.STREAM.value,
                "condition": (
                    "request_id bound to a different run_id — live record "
                    "or cleanup tombstone"
                ),
                "decision": RunDecision.REJECT.value,
                "reason": StreamRejectReason.REQUEST_ID_BOUND_TO_OTHER_RUN.value,
                "response_fields": ["reason", "existing_run_id"],
            }
        ],
    }


def build_invalid() -> dict[str, dict]:
    def case(raw: dict, rationale: str) -> dict:
        return {"rationale": rationale, "request": raw}

    return {
        "extra-top-level-field": case(
            _mutate(lambda r: r.update({"unexpected": SECRET_MARKER})),
            "알 수 없는 최상위 필드는 거부된다 (extra=forbid).",
        ),
        "jwt-field": case(
            _mutate(lambda r: r.update({"user_jwt": SECRET_MARKER})),
            "사용자 JWT 는 계약 경계에서 거부된다.",
        ),
        "credit-field": case(
            _mutate(lambda r: r.update({"credit_balance": 500})),
            "credit 데이터는 계약에 표현이 없다.",
        ),
        "metadata-field": case(
            _mutate(lambda r: r.update({"metadata": {"note": SECRET_MARKER}})),
            "무제한 metadata 는 계약에 들어올 수 없다.",
        ),
        "attachment-binary": case(
            _mutate(lambda r: r["turn_attachments"][0].update({"data": SECRET_MARKER})),
            "원본 binary 는 계약 경계에서 거부된다.",
        ),
        "unknown-input-field": case(
            _mutate(lambda r: r["input"].update({"prompt_override": "x"})),
            "중첩 모델의 알 수 없는 필드도 거부된다.",
        ),
        "wrong-contract-version": case(
            _mutate(lambda r: r.update({"contract_version": "internal-run-v2"})),
            "알 수 없는 contract_version 은 거부된다.",
        ),
        "bad-sha256": case(
            _mutate(lambda r: r["viewer_context"].update({"sha256": "nothex"})),
            "sha256 은 64자리 소문자 hex 여야 한다.",
        ),
        "disallowed-mime": case(
            _mutate(lambda r: r["turn_attachments"][0].update({"mime": "image/heic"})),
            "MIME allowlist 밖의 형식은 거부된다.",
        ),
        "pdf-with-pixels": case(
            _mutate(
                lambda r: r["turn_attachments"][0].update(
                    {"mime": "application/pdf", "page_count": 5}
                )
            ),
            "PDF asset 은 pixel 필드를 가지지 못한다 (image 필드 잔존).",
        ),
        "image-without-pixels": case(
            _mutate(lambda r: r["turn_attachments"][0].pop("pixel_width")),
            "image asset 은 pixel dimensions 를 모두 가져야 한다.",
        ),
        "pixel-count-mismatch": case(
            _mutate(lambda r: r["turn_attachments"][0].update({"pixel_count": 12345})),
            "pixel_count 는 width*height 와 일치해야 한다.",
        ),
        "history-echoes-input": case(
            _mutate(
                lambda r: r["history"].append(
                    {
                        "message_id": "msg-900",
                        "role": "user",
                        "text": r["input"]["text"],
                    }
                )
            ),
            "history 는 현재 input 을 중복하면 안 된다.",
        ),
        "duplicate-requested-tasks": case(
            _mutate(
                lambda r: r["input"].update({"requested_tasks": ["solve", "solve"]})
            ),
            "정규화된 task 배열은 중복을 허용하지 않는다.",
        ),
        "naive-deadline": case(
            _mutate(
                lambda r: r["execution_options"].update(
                    {"request_deadline_at": "2026-10-02T09:30:00"}
                )
            ),
            "deadline 은 timezone 을 가져야 한다 (offset-aware).",
        ),
        "scope-note-mismatch": case(
            _mutate(lambda r: r["retrieval_scope"].update({"note_id": "note-88"})),
            "retrieval scope 는 요청 Note 안에 있어야 한다.",
        ),
        "conversation-mismatch": case(
            _mutate(lambda r: r.update({"conversation_id": "conv-1"})),
            "v1 은 note_id 를 canonical conversation_id 로 쓴다.",
        ),
        "dangling-source-message": case(
            _mutate(
                lambda r: r["turn_attachment_refs"][0].update(
                    {"source_message_id": "msg-999"}
                )
            ),
            "참조의 source_message 는 전달된 history 안에 있어야 한다.",
        ),
        "empty-text": case(
            _mutate(lambda r: r["input"].update({"text": "  "})),
            "input.text 는 비어 있을 수 없다.",
        ),
        "attempt-zero": case(
            _mutate(lambda r: r.update({"attempt": 0})),
            "attempt 는 1 이상이다.",
        ),
        "viewer-page-out-of-range": case(
            _mutate(lambda r: r["viewer_context"].update({"page": 99})),
            "viewer page 는 문서 page_count 를 넘을 수 없다.",
        ),
        "self-revising-solution": case(
            _mutate(
                lambda r: r["verified_solution_refs"][0].update(
                    {"revises_solution_id": "solution-7"}
                )
            ),
            "solution 참조는 자기 자신을 revise 할 수 없다.",
        ),
        "duplicate-history-message-id": case(
            _mutate(
                lambda r: r["history"].append(
                    {
                        "message_id": "msg-101",
                        "role": "user",
                        "text": "다른 내용",
                    }
                )
            ),
            "history message_id 는 유일해야 한다.",
        ),
    }


def build_canonical_rules() -> dict:
    return {
        "version": "canonical-json-v1",
        "rules": [
            "UTF-8 출력, 공백 없음",
            "객체 멤버는 키 코드포인트 순 정렬, null 멤버 생략",
            '문자열은 " \\ \b \f \n \r \t 와 U+0020 미만 제어문자만 '
            "\\uXXXX 로 이스케이프, 비ASCII는 리터럴",
            "정수는 평문 base-10, 비정수 double 은 shortest round-trip "
            "평문(지수 표기 없음)",
            "fingerprint 입력은 파싱 정규화 후의 모델 값이다 — 문자열 필드는 "
            "양 끝의 Unicode whitespace(Python str.strip() 집합: U+0009–000D, "
            "U+001C–001F, U+0085, U+00A0, U+1680, U+2000–200A, U+2028, U+2029, "
            "U+202F, U+205F, U+3000)를 제거한 뒤 들어간다. Java String.strip()은 "
            "이 집합보다 좁으므로 소비자는 같은 집합을 그대로 구현해야 한다",
            "shortest round-trip 자릿수는 비정규(subnormal) double 에서 "
            "언어별로 다를 수 있다 — v1 입력은 정상 범위 double 만 기대하며, "
            "그 밖의 입력은 계약 갱신 사유다",
        ],
        "normalization_cases": [
            {"wire": "\u00a0padded\u3000", "normalized": "padded"},
            {"wire": "\u3000한국어\u00a0", "normalized": "한국어"},
            {"wire": "no-padding", "normalized": "no-padding"},
        ],
        "number_cases": [
            {"input": 0, "canonical": "0"},
            {"input": -3, "canonical": "-3"},
            {"input": 42, "canonical": "42"},
            {"input": 0.5, "canonical": "0.5"},
            {"input": -0.25, "canonical": "-0.25"},
            {"input": 0.00001, "canonical": "0.00001"},
            {
                "input": 0.30000000000000004,
                "canonical": "0.30000000000000004",
            },
            {"input": 1.0, "canonical": "1"},
            {"input": -0.0, "canonical": "0"},
        ],
        "string_cases": [
            {"input": "plain", "canonical": '"plain"'},
            {
                "input": 'a\nb\t"q"\\z',
                "canonical": '"a\\nb\\t\\"q\\"\\\\z"',
            },
            {"input": "한국어 é ✓", "canonical": '"한국어 é ✓"'},
            {"input": "", "canonical": '"\\u0001\\u001f"'},
        ],
        "document_cases": [
            {"input": {"b": 1, "a": 2}, "canonical": '{"a":2,"b":1}'},
            {"input": {"a": None, "b": []}, "canonical": '{"b":[]}'},
            {
                "input": {"list": [True, False, None]},
                "canonical": '{"list":[true,false,null]}',
            },
            {"input": {}, "canonical": "{}"},
        ],
    }


def build_manifest() -> dict:
    return {
        "consumer_version": "internal-run-fixtures-v2",
        "contract_version": "internal-run-v1",
        "fingerprint_algorithm": "sha256-canonical-json-v1",
        "original_path": "tests/fixtures/contracts/internal-run-v1",
        "change_reason": (
            "Task 1.65 stream identity-binding rejection: a request_id bound "
            "to a different run_id refuses with reason "
            "request_id_bound_to_other_run + existing_run_id — "
            "operations/stream-request-id-bound and decisions.json "
            "binding_overrides pin the shape for the Server."
        ),
        "consumption_policy": (
            "Pin internal-run-fixtures-v2 at original_path; changes require "
            "a new fixture version and reason."
        ),
        "ownership": {
            "brain": [
                "contract validation and sanitization",
                "canonical JSON and fingerprint document projection",
                "contention table (operation × record state)",
            ],
            "server": [
                "run_id/request_id/attempt/fence issuance",
                "history truncation within the product budget",
                "asset sha256/media facts/versions",
                "terminal persistence confirmation before cleanup",
            ],
        },
        "boundaries": (
            "Wire contract only — no HTTP endpoints, service auth, lease/"
            "fencing storage, budget recording, or recovery persistence."
        ),
        "requests": sorted(build_requests()),
        "operations": sorted(build_operations()),
        "invalid": sorted(build_invalid()),
        "fingerprint": sorted(build_fingerprint_cases()),
        "canonical_rules": "canonical.json",
        "decisions": "decisions.json",
        "change_history": [
            {
                "date": "2026-10-02",
                "reason": "Initial task 1.43 freeze.",
            },
            {
                "date": "2026-10-05",
                "reason": (
                    "Task 1.47: status-terminal-canceled adds the stored-only "
                    "run.canceled terminal variant to RunStatusResponse — "
                    "additive under internal-run-v1; no existing fixture "
                    "changed."
                ),
            },
            {
                "date": "2026-10-08",
                "reason": (
                    "Task 1.65: a stream whose request_id is bound to a "
                    "different run_id rejects 409 with reason "
                    "request_id_bound_to_other_run and existing_run_id "
                    "naming the owning run — operations gains "
                    "stream-request-id-bound, decisions.json gains "
                    "binding_overrides (run-decisions-v2). Additive under "
                    "internal-run-v1; no existing fixture changed."
                ),
            },
        ],
        "validation_scope": (
            "Python validators enforce identifier/media/scope/reference "
            "integrity; Java validates against the same canonical JSON and "
            "fingerprint expectations."
        ),
        "fixture_inputs": (
            "Synthetic requests only; no evals oracle or answer metadata."
        ),
    }


def _save(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(data, ensure_ascii=False, indent=2)
    # Keep invisible padding visible: escape the whitespace characters the
    # normalization rule names so fixtures survive editors and pipelines.
    for char in ("\u00a0", "\u3000", "\u1680", "\u202f", "\u205f"):
        text = text.replace(char, f"\\u{ord(char):04x}")
    path.write_text(text + "\n", encoding="utf-8")


def build() -> None:
    for name, payload in build_requests().items():
        _save(ROOT / "requests" / f"{name}.json", payload)
    for name, payload in build_operations().items():
        _save(ROOT / "operations" / f"{name}.json", payload)
    for name, payload in build_invalid().items():
        _save(ROOT / "invalid" / f"{name}.json", payload)
    for name, payload in build_fingerprint_cases().items():
        _save(ROOT / "fingerprint" / f"{name}.json", payload)
    _save(ROOT / "canonical.json", build_canonical_rules())
    _save(ROOT / "decisions.json", build_decisions())
    _save(ROOT / "manifest.json", build_manifest())


if __name__ == "__main__":
    build()
