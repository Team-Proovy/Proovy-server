"""Generate the browser-run-v1 fixtures (task 1.75, Proovy-server #214).

The contract *meaning* is owned by Proovy-server; this directory is only the
versioned artifact both Server and Front consume.  Nothing here is imported
by ``src/`` — Brain does not own the product API.

Activity and terminal frames are derived from the existing activity-v1 source
fixtures and terminal-v2 browser pairs through Brain's own serializers, so the
streams cannot drift from those contracts.  Run ``python build_fixtures.py``
to rewrite every file; ``tests/contracts/test_browser_run_fixtures.py`` fails
when the committed files differ from this builder's output.
"""

from __future__ import annotations

import hashlib
import json
import uuid
from copy import deepcopy
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from proovy_brain.contracts.activity import read_back_problem
from proovy_brain.contracts.browser_solution_check import (
    MAX_SOLUTION_CHECKS,
    DescriptionFormat,
    ResponseKind,
    completion_extension,
)
from proovy_brain.contracts.internal_run_fingerprint import canonical_json_dumps
from proovy_brain.contracts.publication import DisclosureScope
from proovy_brain.contracts.solution_verification import (
    ALLOWED_CHECK_RESULTS,
    MAX_ATTEMPTS,
    MAX_STEPS,
    REQUIRED_FOR_VERIFIED,
    AssuranceScope,
    SolutionVerification,
    StepCheckResult,
    StepKind,
    VerificationStrategy,
    parse_solution_verification,
)
from proovy_brain.contracts.source_progress import (
    SourceEventType,
    derive_source_event_id,
    parse_source_event,
    to_browser_event_payload,
)

ROOT = Path(__file__).resolve().parent
CONTRACTS = ROOT.parent
TERMINAL = CONTRACTS / "terminal-v2"
ACTIVITY = CONTRACTS / "activity-v1/valid"
SOLUTIONS = CONTRACTS / "solution-verification-v1/valid"

CONSUMER_VERSION = "browser-run-fixtures-v1.1"
INITIAL_CONSUMER_VERSION = "browser-run-fixtures-v1"
CONTRACT_VERSION = "browser-run-v1"
FRAME_CONTRACT_VERSION = "brain-source-v1"
FINGERPRINT_ALGORITHM = "browser-request-sha256-canonical-json-v1"
SCHEMA_BASE = "https://proovy.invalid/contracts/"
SCHEMA_ID = SCHEMA_BASE + "browser-run-v1/schemas/browser-run.schema.json"
TERMINAL_SCHEMA_ID = SCHEMA_BASE + "terminal-v2/browser.schema.json"
TERMINAL_REF = "../../terminal-v2/browser.schema.json"

TEXT_MAX = 10_000
LATEX_MAX = 10_000
ATTACHMENT_WIRE_MAX = 10
HEARTBEAT_SECONDS = 15
ACTIVE_LAST_SEQ = 4  # the RUNNING snapshot shared by status/cursor/conversation
MAX_SEQ = 9_007_199_254_740_991

MAX_NOTE_ID = 9_223_372_036_854_775_807  # Java Long.MAX_VALUE / Postgres BIGINT


def _decimal_at_most(limit: int) -> str:
    """Regex for a canonical decimal (no leading zero) in 1..limit."""

    digits = str(limit)
    width = len(digits)
    branches = [f"[1-9][0-9]{{0,{width - 2}}}"]
    for index, char in enumerate(digits):
        low = 1 if index == 0 else 0
        if int(char) > low:
            high = int(char) - 1
            head = str(low) if low == high else f"[{low}-{high}]"
            rest = width - index - 1
            tail = {0: "", 1: "[0-9]"}.get(rest, f"[0-9]{{{rest}}}")
            branches.append(f"{digits[:index]}{head}{tail}")
    branches.append(digits)
    return "(?:" + "|".join(branches) + ")"


# `$` alone also matches before one trailing "\n" (Python re, Java), so every
# anchored pattern ends with an absolute end-of-input lookahead instead.
END = "(?![\\s\\S])"
UUID_BODY = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
UUID_PATTERN = f"^{UUID_BODY}{END}"
NOTE_ID_PATTERN = f"^{_decimal_at_most(MAX_NOTE_ID)}{END}"
OPAQUE_ID_PATTERN = f"^[A-Za-z0-9][A-Za-z0-9_.:-]{{0,127}}{END}"
CURSOR_PATTERN = f"^{UUID_BODY}:{_decimal_at_most(MAX_SEQ)}{END}"
OCCURRED_AT_PATTERN = (
    "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z" + END
)
# Any code point outside the internal-run-v1 strip set (Python str.strip()).
NON_BLANK_PATTERN = (
    "[^\\t\\n\\u000b\\f\\r\\u001c-\\u001f \\u0085\\u00a0\\u1680"
    "\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]"
)
# Phase 2 additive extension (task 2.07).  Keys of the 2.06 record that stay
# internal (docs/plan/27 "화면 노출 정책") and must never appear on a solution
# check or a step, in either spelling.
SOLUTION_INTERNAL_KEYS = (
    "checkReason",
    "check_reason",
    "evidence",
    "evidenceCode",
    "evidence_code",
    "independentAnswer",
    "independentAnswers",
    "failureReason",
    "failure_reason",
    "problemId",
    "problem_id",
    "candidate",
)
# A Read-back is shown before verification: no solution, step, formula or
# answer may ride on it (CONTEXT.md *Read-back*).
READ_BACK_FORBIDDEN_PAYLOAD_KEYS = (
    "answer",
    "finalAnswer",
    "steps",
    "checkableSteps",
    "solutionChecks",
    "sympyStatement",
    "assuranceScope",
    "candidate",
)
READ_BACK_FORBIDDEN_PROBLEM_KEYS = (
    "answer",
    "finalAnswer",
    "answerType",
    "answer_type",
    "problemId",
    "problem_id",
    "steps",
    "checkableSteps",
    "sympyStatement",
    "candidate",
)
EXTENSION_KEYS = ("responseKind", "assuranceScope", "solutionChecks")
ACTIVITY_TYPES = (
    "activity.started",
    "activity.updated",
    "activity.completed",
    "activity.failed",
    "activity.canceled",
)
STATEMENT_TEXT_FIELDS = ("value", "cond", "covers", "lhs", "rhs")

FORBIDDEN_PUBLIC_KEYS = (
    "attempt",
    "fenceToken",
    "fence_token",
    "sourceEventId",
    "source_event_id",
    "failureClass",
    "failure_class",
    "requestFingerprint",
    "usage",
    "deadline",
)
# Server-owned response objects stay additive-tolerant but never carry an
# internal key (same list as frames).
PUBLIC_RESPONSE_OBJECTS = (
    "ErrorResponse",
    "ActiveRunConflict",
    "RunFailureSummary",
    "RunStatus",
    "RunCancelResult",
    "TurnAttachmentView",
    "UserMessageContent",
    "AssistantFailure",
    "AssistantCancel",
    "AssistantMessageContent",
    "ConversationMessage",
    "ActiveRunSummary",
    "ConversationPage",
)
STRIPPED_LENGTH_NOTE = (
    f"1..{TEXT_MAX} code points after stripping the internal-run-v1 strip set "
    "(Python str.strip()); the upper bound is a contract check (RUN4001) "
    "beyond this schema because the raw value may carry surrounding blanks."
)
FORBIDDEN_REQUEST_KEYS = (
    "inputScope",
    "scope",
    "usage",
    "credit",
    "attempt",
    "fenceToken",
    "history",
    "verifiedSolutionRefs",
    "turnAttachmentRefs",
    "retrievalScope",
    "corpusVersion",
    "conversationId",
    "userId",
    "authToken",
    "deadline",
    "budget",
    "runId",
    "locale",
)

PUBLIC_RUN_STATUSES = (
    "PENDING",
    "RUNNING",
    "CANCELING",
    "COMPLETED",
    "FAILED",
    "CANCELED",
)
TERMINAL_TYPES = ("run.completed", "run.failed", "run.canceled")
SERVER_FRAME_TYPES = (
    "run.created",
    "response.page.started",
    "response.page.completed",
    "run.canceling",
    "run.canceled",
)
CANCEL_MESSAGES = {
    "user_requested": "답변 생성을 중단했습니다.",
    "asset_deleting": "참조한 파일이 삭제되어 답변 생성을 중단했습니다.",
    "budget_exhausted": "처리 한도에 도달해 답변 생성을 중단했습니다.",
}
ERRORS = {
    "AUTH4010": (401, "인증 토큰이 필요합니다."),
    "AUTH4012": (401, "토큰이 만료되었습니다."),
    "AUTH4013": (401, "유효하지 않은 토큰입니다."),
    "RUN4001": (400, "요청 형식이 올바르지 않습니다."),
    "RUN4002": (400, "지원하지 않는 요청 항목이 포함되어 있습니다."),
    "RUN4003": (400, "이벤트 재개 위치가 올바르지 않습니다."),
    "RUN4004": (400, "다른 실행의 이벤트 재개 위치입니다."),
    "RUN4021": (402, "사용 가능한 잔여 사용량이 부족합니다."),
    "RUN4041": (404, "대상을 찾을 수 없습니다."),
    "RUN4091": (409, "이 노트에서 진행 중인 답변이 있습니다."),
    "RUN4092": (409, "이미 사용된 요청 ID입니다."),
    "RUN4221": (422, "첨부 또는 참조한 자료를 사용할 수 없습니다."),
    "RUN4291": (429, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
}
SUCCESS_MESSAGE = "요청에 성공했습니다."


def fixed_uuid(name: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"https://proovy.invalid/{name}"))


REQUEST_ID = fixed_uuid("request/primary")
OTHER_REQUEST_ID = fixed_uuid("request/other")
RUN_ID = fixed_uuid("run/primary")
ACTIVE_RUN_ID = fixed_uuid("run/active")
OTHER_RUN_ID = fixed_uuid("run/other")
NOTE_ID = "123"
USER_MESSAGE_ID = fixed_uuid("message/user")
ASSISTANT_MESSAGE_ID = fixed_uuid("message/assistant")
ATTACHMENT_ID = fixed_uuid("attachment/1")
ATTACHMENT_ID_2 = fixed_uuid("attachment/2")
NOTE_ASSET_ID = "asset-9f8c"
ASTRAL = "\U0001d465"  # MATHEMATICAL ITALIC SMALL X: 1 code point, 2 UTF-16 units
START = datetime(2026, 10, 17, 9, tzinfo=UTC)


def scenario_ids(key: str) -> dict[str, str]:
    """IDs of one run scenario; every fixture describing that run reuses them."""

    if key == "primary":
        return {
            "runId": RUN_ID,
            "requestId": REQUEST_ID,
            "userMessageId": USER_MESSAGE_ID,
            "assistantMessageId": ASSISTANT_MESSAGE_ID,
        }
    return {
        "runId": fixed_uuid(f"run/{key}"),
        "requestId": fixed_uuid(f"request/{key}"),
        "userMessageId": fixed_uuid(f"message/user/{key}"),
        "assistantMessageId": fixed_uuid(f"message/assistant/{key}"),
    }


ACTIVE = scenario_ids("active")
assert ACTIVE["runId"] == ACTIVE_RUN_ID


def iso(moment: datetime) -> str:
    return moment.isoformat().replace("+00:00", "Z")


# --------------------------------------------------------------------------
# schema
# --------------------------------------------------------------------------


def _ref(name: str) -> dict[str, str]:
    return {"$ref": f"#/$defs/{name}"}


def _terminal(name: str) -> dict[str, str]:
    return {"$ref": f"{TERMINAL_REF}#/$defs/{name}"}


def _nullable(schema: dict[str, Any]) -> dict[str, Any]:
    return {"anyOf": [schema, {"type": "null"}]}


def _forbid(keys: tuple[str, ...]) -> dict[str, Any]:
    return {"not": {"anyOf": [{"required": [key]} for key in keys]}}


def _frame(event_type: str, payload: str) -> dict[str, Any]:
    return {
        "type": "object",
        "additionalProperties": True,
        "required": [
            "contractVersion",
            "runId",
            "seq",
            "occurredAt",
            "type",
            "payload",
        ],
        "properties": {
            "contractVersion": {"const": FRAME_CONTRACT_VERSION},
            "runId": _ref("Uuid"),
            "seq": _ref("Seq"),
            "occurredAt": _ref("OccurredAt"),
            "type": {"const": event_type},
            "payload": _ref(payload),
        },
    }


def _success(result: str) -> dict[str, Any]:
    return {
        "type": "object",
        "additionalProperties": True,
        "required": ["isSuccess", "code", "message", "result"],
        "properties": {
            "isSuccess": {"const": True},
            "code": {"const": "COMMON200"},
            "message": {"const": SUCCESS_MESSAGE},
            "result": _ref(result),
        },
    }


def _when(prop: str, values: list[str], then: dict[str, Any]) -> dict[str, Any]:
    return {
        "if": {"required": [prop], "properties": {prop: {"enum": values}}},
        "then": then,
    }


def _scope_is(value: str) -> dict[str, Any]:
    return {"properties": {"assuranceScope": {"const": value}}}


def _contains_scope(value: str) -> dict[str, Any]:
    return {"properties": {"solutionChecks": {"contains": _scope_is(value)}}}


def _all_scopes(value: str) -> dict[str, Any]:
    return {"properties": {"solutionChecks": {"items": _scope_is(value)}}}


def _steps_withheld(*, withheld: bool) -> dict[str, Any]:
    return {
        "properties": {
            "checkableSteps": {
                "minItems": 1,
                "items": {"properties": {"withheld": {"const": withheld}}},
            }
        }
    }


def _phase2_defs() -> dict[str, Any]:
    """browser-run-fixtures-v1.1 additive definitions (task 2.07, spec 04).

    Mirrors the 2.06 model rules (``ALLOWED_CHECK_RESULTS``,
    ``REQUIRED_FOR_VERIFIED``, strategy/scope conditions) so consumers can
    reject an inconsistent step or badge with the schema alone.
    """

    withheld_step = {
        "properties": {
            "description": {"type": "null"},
            "descriptionFormat": {"type": "null"},
            "sympyStatement": {"type": "null"},
        }
    }
    public_step = {
        "properties": {
            "description": {"type": "string"},
            "descriptionFormat": {"enum": [f.value for f in DescriptionFormat]},
        }
    }
    step_rules: list[dict[str, Any]] = [
        _forbid(SOLUTION_INTERNAL_KEYS),
        {
            "if": {
                "required": ["withheld"],
                "properties": {"withheld": {"const": True}},
            },
            "then": withheld_step,
            "else": public_step,
        },
    ]
    for kind in StepKind:
        allowed = [r.value for r in StepCheckResult if r in ALLOWED_CHECK_RESULTS[kind]]
        then: dict[str, Any] = {"properties": {"checkResult": {"enum": allowed}}}
        if kind is StepKind.ARGUE:
            then["properties"]["sympyStatement"] = {"type": "null"}
        step_rules.append(
            {
                "if": {
                    "required": ["stepKind"],
                    "properties": {"stepKind": {"const": kind.value}},
                },
                "then": then,
            }
        )
    for kind, fields in REQUIRED_FOR_VERIFIED.items():
        step_rules.append(
            {
                "if": {
                    "required": ["stepKind", "checkResult", "withheld"],
                    "properties": {
                        "stepKind": {"const": kind.value},
                        "checkResult": {"const": StepCheckResult.VERIFIED.value},
                        "withheld": {"const": False},
                    },
                },
                "then": {
                    "properties": {
                        "sympyStatement": {"type": "object", "required": list(fields)}
                    }
                },
            }
        )
    falsified = {
        "contains": {
            "properties": {"checkResult": {"const": StepCheckResult.FALSIFIED.value}}
        }
    }
    cross = VerificationStrategy.ANSWER_CROSS_CHECK.value
    return {
        "AssuranceScope": {
            "description": "Response/solution badge (ADR 0017 §2): 검증됨·미검증·검증 대상 아님.",
            "enum": [scope.value for scope in AssuranceScope],
        },
        "ResponseKind": {
            "description": "re_explanation = 기존 풀이 재설명(검증 없이 스트리밍, Front는 "
            "'추가 설명' 표시). 부재는 이 확장 이전 응답이다.",
            "enum": [kind.value for kind in ResponseKind],
        },
        "StepKind": {"enum": [kind.value for kind in StepKind]},
        "Disclosure": {
            "description": "The turn's answer-disclosure scope (Brain "
            "DisclosureScope; the same one assistantResponse was published under).",
            "enum": [scope.value for scope in DisclosureScope],
        },
        "StepCheckResult": {
            "description": "verified=✓, falsified=✗, unparseable=검산 안 됨, "
            "not_checked=검산 대상 아님.",
            "enum": [result.value for result in StepCheckResult],
        },
        "SympyStatement": {
            "description": "The formula the deterministic checker checked; which "
            "fields matter depends on stepKind (define: name+value, given: cond, "
            "case: cond(+covers), claim: lhs+rhs(+name), choose: value(+choice)).",
            "type": "object",
            "additionalProperties": True,
            "anyOf": [
                {"required": [f]} for f in ("name", *STATEMENT_TEXT_FIELDS, "choice")
            ],
            "properties": {
                "name": {"type": "string", "minLength": 1, "maxLength": 64},
                **{
                    f: {"type": "string", "minLength": 1, "maxLength": 2_000}
                    for f in STATEMENT_TEXT_FIELDS
                },
                "choice": {"type": "integer", "minimum": 1, "maximum": 5},
            },
        },
        "CheckableStep": {
            "description": "One rendered solution step: the screen shows "
            "description (SafeMarkdown without source markers, or literal text when "
            "descriptionFormat is plain_text) with its ✓·✗ mark. withheld=true "
            "(disclosure hide_answer): kind and mark only — description, "
            "descriptionFormat and sympyStatement are null. checkReason never "
            "appears.",
            "type": "object",
            "additionalProperties": True,
            "required": [
                "stepIndex",
                "withheld",
                "description",
                "descriptionFormat",
                "sympyStatement",
                "stepKind",
                "checkResult",
            ],
            "properties": {
                "stepIndex": {
                    "type": "integer",
                    "minimum": 0,
                    "maximum": MAX_STEPS - 1,
                },
                "withheld": {"type": "boolean"},
                "description": _nullable(
                    {"type": "string", "minLength": 1, "maxLength": 10_000}
                ),
                "descriptionFormat": _nullable(
                    {"enum": [f.value for f in DescriptionFormat]}
                ),
                "sympyStatement": _nullable(_ref("SympyStatement")),
                "stepKind": _ref("StepKind"),
                "checkResult": _ref("StepCheckResult"),
            },
            "allOf": step_rules,
        },
        "CrossCheckSummary": {
            "description": "Agreement flags only — the independent answer and "
            "its program stay internal (docs/plan/27).",
            "type": "object",
            "additionalProperties": True,
            "required": ["independentAnswerAgrees", "stepCheckCompleted"],
            "properties": {
                "independentAnswerAgrees": {"type": "boolean"},
                "stepCheckCompleted": {"type": "boolean"},
            },
            "allOf": [_forbid(SOLUTION_INTERNAL_KEYS)],
        },
        "SolutionCheck": {
            "type": "object",
            "additionalProperties": True,
            "description": "One verified solution. Order = Read-back problems order "
            "= response order (problemNumber may be null for text input). "
            "disclosure is the turn's answer-disclosure scope: full = steps public; "
            "hide_answer = every step withheld (kind and mark only); hide_solution "
            "= no steps.",
            "required": [
                "problemNumber",
                "assuranceScope",
                "strategy",
                "attempts",
                "crossCheck",
                "disclosure",
                "checkableSteps",
            ],
            "properties": {
                "problemNumber": _nullable(
                    {"type": "string", "minLength": 1, "maxLength": 32}
                ),
                "assuranceScope": _ref("AssuranceScope"),
                "strategy": {"enum": [s.value for s in VerificationStrategy]},
                "attempts": {"type": "integer", "minimum": 1, "maximum": MAX_ATTEMPTS},
                "crossCheck": _nullable(_ref("CrossCheckSummary")),
                "disclosure": _ref("Disclosure"),
                "checkableSteps": {
                    "type": "array",
                    "maxItems": MAX_STEPS,
                    "items": _ref("CheckableStep"),
                },
            },
            "allOf": [
                _forbid(SOLUTION_INTERNAL_KEYS),
                _when(
                    "disclosure",
                    [DisclosureScope.FULL.value],
                    _steps_withheld(withheld=False),
                ),
                _when(
                    "disclosure",
                    [DisclosureScope.HIDE_ANSWER.value],
                    _steps_withheld(withheld=True),
                ),
                _when(
                    "disclosure",
                    [DisclosureScope.HIDE_SOLUTION.value],
                    {"properties": {"checkableSteps": {"maxItems": 0}}},
                ),
                _when(
                    "strategy",
                    [VerificationStrategy.NONE.value],
                    {
                        "properties": {
                            "assuranceScope": {"const": "not_applicable"},
                            "crossCheck": {"type": "null"},
                        }
                    },
                ),
                _when(
                    "strategy",
                    [cross],
                    {
                        "properties": {
                            "assuranceScope": {"enum": ["verified", "unverified"]},
                            "crossCheck": {"type": "object"},
                        }
                    },
                ),
                {
                    "if": {
                        "required": ["assuranceScope", "strategy"],
                        "properties": {
                            "assuranceScope": {"const": "verified"},
                            "strategy": {"const": cross},
                        },
                    },
                    "then": {
                        "properties": {
                            "crossCheck": {
                                "properties": {
                                    "independentAnswerAgrees": {"const": True},
                                    "stepCheckCompleted": {"const": True},
                                }
                            },
                            "checkableSteps": {"not": falsified},
                        }
                    },
                },
                {
                    "if": {
                        "required": ["assuranceScope", "strategy"],
                        "properties": {
                            "assuranceScope": {"const": "unverified"},
                            "strategy": {"const": cross},
                        },
                    },
                    "then": {
                        "anyOf": [
                            {
                                "properties": {
                                    "crossCheck": {
                                        "properties": {
                                            "independentAnswerAgrees": {"const": False}
                                        }
                                    }
                                }
                            },
                            {
                                "properties": {
                                    "crossCheck": {
                                        "properties": {
                                            "stepCheckCompleted": {"const": False}
                                        }
                                    }
                                }
                            },
                            {"properties": {"checkableSteps": falsified}},
                            {
                                "properties": {
                                    "disclosure": {
                                        "const": DisclosureScope.HIDE_SOLUTION.value
                                    }
                                }
                            },
                        ]
                    },
                },
            ],
        },
        "CompletionExtension": {
            "description": "browser-run-fixtures-v1.1 additive fields of run.completed "
            "payload and conversation AssistantMessageContent. All three appear "
            "together or not at all (absent = response from before this extension). "
            "Badge (not_applicable solutions are left out of the count): unverified "
            "iff any solution is unverified; else verified iff any is verified; "
            "else not_applicable (all not_applicable, or none). "
            "solutionChecks order = Read-back problems order = response order.",
            "type": "object",
            "properties": {
                "responseKind": _ref("ResponseKind"),
                "assuranceScope": _ref("AssuranceScope"),
                "solutionChecks": {
                    "type": "array",
                    "maxItems": MAX_SOLUTION_CHECKS,
                    "items": _ref("SolutionCheck"),
                },
            },
            "dependentRequired": {
                key: [other for other in EXTENSION_KEYS if other != key]
                for key in EXTENSION_KEYS
            },
            "allOf": [
                _when(
                    "responseKind",
                    [ResponseKind.RE_EXPLANATION.value],
                    {
                        "properties": {
                            "assuranceScope": {"const": "not_applicable"},
                            "solutionChecks": {"maxItems": 0},
                        }
                    },
                ),
                _when(
                    "assuranceScope",
                    ["verified"],
                    {
                        "allOf": [
                            _contains_scope("verified"),
                            {
                                "properties": {
                                    "solutionChecks": {
                                        "not": {"contains": _scope_is("unverified")}
                                    }
                                }
                            },
                        ]
                    },
                ),
                _when(
                    "assuranceScope", ["not_applicable"], _all_scopes("not_applicable")
                ),
                _when(
                    "assuranceScope",
                    ["unverified"],
                    _contains_scope("unverified"),
                ),
            ],
        },
        "ReadBackCompletedPayload": {
            "description": "Activity Kind read_back (Phase 2 Read-back, '이렇게 "
            "읽었어요'): the activity.completed payload with problems[] of "
            "problemNumber/questionText/choices/figureDescription. Defined in "
            "terminal-v2 browser schema (generated from Brain activity.py).",
            "$ref": f"{TERMINAL_REF}#/$defs/ReadBackCompleted",
        },
        "ReadBackGuard": {
            "description": "A read-back precedes verification: no solution, step, "
            "formula, answer or internal problem id on it.",
            "allOf": [
                _forbid(READ_BACK_FORBIDDEN_PAYLOAD_KEYS),
                {
                    "properties": {
                        "problems": {"items": _forbid(READ_BACK_FORBIDDEN_PROBLEM_KEYS)}
                    }
                },
            ],
        },
    }


def schema() -> dict[str, Any]:
    string_array = {"type": "array", "uniqueItems": True}
    defs: dict[str, Any] = {
        "Uuid": {"type": "string", "pattern": UUID_PATTERN},
        "NoteId": {"type": "string", "pattern": NOTE_ID_PATTERN},
        "OpaqueId": {"type": "string", "pattern": OPAQUE_ID_PATTERN},
        "Seq": {"type": "integer", "minimum": 1, "maximum": MAX_SEQ},
        "EventId": {"type": "string", "pattern": CURSOR_PATTERN},
        "OccurredAt": {"type": "string", "pattern": OCCURRED_AT_PATTERN},
        "PublicRunStatus": {"enum": list(PUBLIC_RUN_STATUSES)},
        "ActiveRunStatus": {"enum": ["PENDING", "RUNNING", "CANCELING"]},
        "RequestedTask": {"enum": ["solve", "check", "explain"]},
        "RequestedOutput": {
            "description": "video is a known value but Phase-1 gated: RUN4002.",
            "enum": ["text", "video"],
        },
        "CancelCause": {"enum": list(CANCEL_MESSAGES)},
        "AttachmentMime": {
            "enum": ["application/pdf", "image/jpeg", "image/png", "image/webp"]
        },
        "PageSelection": {
            "description": "page-selection-v1; left<right and top<bottom are "
            "contract checks (RUN4001) beyond this schema.",
            "type": "object",
            "additionalProperties": False,
            "required": ["left", "top", "right", "bottom"],
            "properties": {
                "left": {"type": "number", "minimum": 0, "exclusiveMaximum": 1},
                "top": {"type": "number", "minimum": 0, "exclusiveMaximum": 1},
                "right": {"type": "number", "exclusiveMinimum": 0, "maximum": 1},
                "bottom": {"type": "number", "exclusiveMinimum": 0, "maximum": 1},
            },
        },
        "ViewerFocus": {
            "type": "object",
            "additionalProperties": False,
            "required": ["noteAssetId", "page"],
            "properties": {
                "noteAssetId": _ref("OpaqueId"),
                "page": {"type": "integer", "minimum": 1},
                "selection": _nullable(_ref("PageSelection")),
            },
        },
        "ConversationRunRequest": {
            "description": "POST /api/conversations body. Closed: unknown or "
            "forbidden fields are RUN4001.",
            "type": "object",
            "additionalProperties": False,
            "required": ["requestId", "text"],
            "properties": {
                "requestId": _ref("Uuid"),
                "noteId": _ref("NoteId"),
                "text": {
                    "description": STRIPPED_LENGTH_NOTE,
                    "type": "string",
                    "minLength": 1,
                    "pattern": NON_BLANK_PATTERN,
                },
                "latex": _nullable(
                    {
                        "description": STRIPPED_LENGTH_NOTE,
                        "type": "string",
                        "minLength": 1,
                        "pattern": NON_BLANK_PATTERN,
                    }
                ),
                "requestedTasks": {
                    **string_array,
                    "maxItems": 3,
                    "items": _ref("RequestedTask"),
                },
                "requestedOutputs": {
                    **string_array,
                    "maxItems": 2,
                    "items": _ref("RequestedOutput"),
                },
                "turnAttachmentIds": {
                    **string_array,
                    "maxItems": ATTACHMENT_WIRE_MAX,
                    "items": _ref("Uuid"),
                },
                "viewerFocus": _nullable(_ref("ViewerFocus")),
            },
        },
        "ErrorResponse": {
            "type": "object",
            "additionalProperties": True,
            "required": ["isSuccess", "code", "message"],
            "properties": {
                "isSuccess": {"const": False},
                "code": {"enum": list(ERRORS)},
                "message": {"type": "string", "minLength": 1},
                "result": _ref("ActiveRunConflict"),
            },
            "allOf": [
                {
                    "if": {"properties": {"code": {"const": code}}},
                    "then": {"properties": {"message": {"const": message}}},
                }
                for code, (_, message) in ERRORS.items()
            ]
            + [
                {
                    "if": {"properties": {"code": {"const": "RUN4091"}}},
                    "then": {"required": ["result"]},
                    "else": {"not": {"required": ["result"]}},
                }
            ],
        },
        "ActiveRunConflict": {
            "type": "object",
            "additionalProperties": True,
            "required": ["activeRunId"],
            "properties": {"activeRunId": _ref("Uuid")},
        },
        "RunFailureSummary": {
            "type": "object",
            "additionalProperties": True,
            "required": ["code", "reason", "recoverable"],
            "properties": {
                "code": _terminal("FailureCode"),
                "reason": _nullable(_terminal("AttachmentFailureReason")),
                "recoverable": {"type": "boolean"},
            },
        },
        "RunStatus": {
            "description": "Internal RETRYING is projected to RUNNING.",
            "type": "object",
            "additionalProperties": True,
            "required": [
                "runId",
                "requestId",
                "noteId",
                "userMessageId",
                "assistantMessageId",
                "status",
                "cancelable",
                "lastSeq",
                "responseDisposition",
                "failure",
                "createdAt",
                "completedAt",
            ],
            "properties": {
                "runId": _ref("Uuid"),
                "requestId": _ref("Uuid"),
                "noteId": _ref("NoteId"),
                "userMessageId": _ref("Uuid"),
                "assistantMessageId": _ref("Uuid"),
                "status": _ref("PublicRunStatus"),
                "cancelable": {"type": "boolean"},
                "lastSeq": _ref("Seq"),
                "responseDisposition": _nullable(_terminal("ResponseDisposition")),
                "failure": _nullable(_ref("RunFailureSummary")),
                "createdAt": _ref("OccurredAt"),
                "completedAt": _nullable(_ref("OccurredAt")),
            },
            "allOf": [
                _when(
                    "status",
                    ["PENDING", "RUNNING"],
                    {
                        "properties": {
                            "cancelable": {"const": True},
                            "responseDisposition": {"type": "null"},
                            "failure": {"type": "null"},
                            "completedAt": {"type": "null"},
                        }
                    },
                ),
                _when(
                    "status",
                    ["CANCELING"],
                    {
                        "properties": {
                            "cancelable": {"const": False},
                            "responseDisposition": {"type": "null"},
                            "failure": {"type": "null"},
                            "completedAt": {"type": "null"},
                        }
                    },
                ),
                _when(
                    "status",
                    ["COMPLETED"],
                    {
                        "properties": {
                            "cancelable": {"const": False},
                            "responseDisposition": {"not": {"type": "null"}},
                            "failure": {"type": "null"},
                            "completedAt": {"not": {"type": "null"}},
                        }
                    },
                ),
                _when(
                    "status",
                    ["FAILED"],
                    {
                        "properties": {
                            "cancelable": {"const": False},
                            "responseDisposition": {"type": "null"},
                            "failure": {"not": {"type": "null"}},
                            "completedAt": {"not": {"type": "null"}},
                        }
                    },
                ),
                _when(
                    "status",
                    ["CANCELED"],
                    {
                        "properties": {
                            "cancelable": {"const": False},
                            "responseDisposition": {"type": "null"},
                            "failure": {"type": "null"},
                            "completedAt": {"not": {"type": "null"}},
                        }
                    },
                ),
            ],
        },
        "RunCancelResult": {
            "type": "object",
            "additionalProperties": True,
            "required": ["runId", "status", "outcome"],
            "properties": {
                "runId": _ref("Uuid"),
                "status": _ref("PublicRunStatus"),
                "outcome": {
                    "enum": ["accepted", "already_canceling", "already_terminal"]
                },
            },
            "allOf": [
                _when(
                    "outcome",
                    ["accepted"],
                    {"properties": {"status": {"enum": ["CANCELING", "CANCELED"]}}},
                ),
                _when(
                    "outcome",
                    ["already_canceling"],
                    {"properties": {"status": {"const": "CANCELING"}}},
                ),
                _when(
                    "outcome",
                    ["already_terminal"],
                    {
                        "properties": {
                            "status": {"enum": ["COMPLETED", "FAILED", "CANCELED"]}
                        }
                    },
                ),
            ],
        },
        "TurnAttachmentView": {
            "type": "object",
            "additionalProperties": True,
            "required": ["attachmentId", "mime", "displayName"],
            "properties": {
                "attachmentId": _ref("Uuid"),
                "mime": _ref("AttachmentMime"),
                "displayName": _nullable(
                    {"type": "string", "minLength": 1, "maxLength": 255}
                ),
            },
        },
        "UserMessageContent": {
            "type": "object",
            "additionalProperties": True,
            "required": [
                "text",
                "latex",
                "requestedTasks",
                "requestedOutputs",
                "turnAttachments",
                "viewerFocus",
            ],
            "properties": {
                "text": {"type": "string", "minLength": 1},
                "latex": _nullable({"type": "string", "minLength": 1}),
                "requestedTasks": {**string_array, "items": _ref("RequestedTask")},
                "requestedOutputs": {**string_array, "items": {"const": "text"}},
                "turnAttachments": {
                    "type": "array",
                    "items": _ref("TurnAttachmentView"),
                },
                "viewerFocus": _nullable(_ref("ViewerFocus")),
            },
        },
        "AssistantFailure": {
            "type": "object",
            "additionalProperties": True,
            "required": ["code", "reason", "message", "recoverable"],
            "properties": {
                "code": _terminal("FailureCode"),
                "reason": _nullable(_terminal("AttachmentFailureReason")),
                "message": {"type": "string", "minLength": 1, "maxLength": 500},
                "recoverable": {"type": "boolean"},
            },
        },
        "AssistantCancel": {
            "type": "object",
            "additionalProperties": True,
            "required": ["cause", "message"],
            "properties": {
                "cause": _ref("CancelCause"),
                "message": {"type": "string"},
            },
            "allOf": [
                {
                    "if": {"properties": {"cause": {"const": cause}}},
                    "then": {"properties": {"message": {"const": message}}},
                }
                for cause, message in CANCEL_MESSAGES.items()
            ],
        },
        "AssistantMessageContent": {
            "type": "object",
            "additionalProperties": True,
            "required": [
                "responseDisposition",
                "clarificationReason",
                "assistantResponse",
                "verificationSummary",
                "failure",
                "cancel",
            ],
            "properties": {
                "responseDisposition": _nullable(_terminal("ResponseDisposition")),
                "clarificationReason": _nullable(_terminal("ClarificationReason")),
                "assistantResponse": _nullable(_terminal("AssistantResponseV1")),
                "verificationSummary": {
                    "type": "array",
                    "items": _terminal("SolutionVerificationSummary"),
                },
                "failure": _nullable(_ref("AssistantFailure")),
                "cancel": _nullable(_ref("AssistantCancel")),
            },
            "allOf": [_ref("CompletionExtension")],
        },
        "ConversationMessage": {
            "type": "object",
            "additionalProperties": True,
            "required": ["messageId", "role", "status", "runId", "createdAt"],
            "properties": {
                "messageId": _ref("Uuid"),
                "role": {"enum": ["user", "assistant"]},
                "status": {
                    "enum": ["PENDING", "STREAMING", "COMPLETED", "FAILED", "CANCELED"]
                },
                "runId": _ref("Uuid"),
                "createdAt": _ref("OccurredAt"),
                "user": _ref("UserMessageContent"),
                "assistant": _ref("AssistantMessageContent"),
            },
            "allOf": [
                _when(
                    "role",
                    ["user"],
                    {
                        "required": ["user"],
                        "not": {"required": ["assistant"]},
                        "properties": {"status": {"const": "COMPLETED"}},
                    },
                ),
                _when(
                    "role",
                    ["assistant"],
                    {"required": ["assistant"], "not": {"required": ["user"]}},
                ),
                _when(
                    "status",
                    ["PENDING", "STREAMING"],
                    {
                        "properties": {
                            "assistant": {
                                "properties": {
                                    "responseDisposition": {"type": "null"},
                                    "assistantResponse": {"type": "null"},
                                    "failure": {"type": "null"},
                                    "cancel": {"type": "null"},
                                }
                            }
                        }
                    },
                ),
                _when(
                    "status",
                    ["FAILED"],
                    {
                        "properties": {
                            "assistant": {
                                "properties": {
                                    "assistantResponse": {"type": "null"},
                                    "failure": {"not": {"type": "null"}},
                                    "cancel": {"type": "null"},
                                }
                            }
                        }
                    },
                ),
                _when(
                    "status",
                    ["CANCELED"],
                    {
                        "properties": {
                            "assistant": {
                                "properties": {
                                    "assistantResponse": {"type": "null"},
                                    "failure": {"type": "null"},
                                    "cancel": {"not": {"type": "null"}},
                                }
                            }
                        }
                    },
                ),
                {
                    "if": {
                        "required": ["role", "status"],
                        "properties": {
                            "role": {"const": "assistant"},
                            "status": {"const": "COMPLETED"},
                        },
                    },
                    "then": {
                        "properties": {
                            "assistant": {
                                "properties": {
                                    "responseDisposition": {"not": {"type": "null"}},
                                    "assistantResponse": {"not": {"type": "null"}},
                                    "failure": {"type": "null"},
                                    "cancel": {"type": "null"},
                                }
                            }
                        }
                    },
                },
            ],
        },
        "ActiveRunSummary": {
            "type": "object",
            "additionalProperties": True,
            "required": ["runId", "status", "lastSeq"],
            "properties": {
                "runId": _ref("Uuid"),
                "status": _ref("ActiveRunStatus"),
                "lastSeq": _ref("Seq"),
            },
        },
        "ConversationPage": {
            "type": "object",
            "additionalProperties": True,
            "required": ["noteId", "activeRun", "messages", "nextBefore"],
            "properties": {
                "noteId": _ref("NoteId"),
                "activeRun": _nullable(_ref("ActiveRunSummary")),
                "messages": {
                    "type": "array",
                    "maxItems": 100,
                    "items": _ref("ConversationMessage"),
                },
                "nextBefore": _nullable(_ref("Uuid")),
            },
        },
        "RunStatusResponse": _success("RunStatus"),
        "RunCancelResponse": _success("RunCancelResult"),
        "ConversationPageResponse": _success("ConversationPage"),
        "RunCreatedPayload": {
            "type": "object",
            "additionalProperties": True,
            "required": [
                "requestId",
                "noteId",
                "userMessageId",
                "assistantMessageId",
                "status",
                "requestedTasks",
                "requestedOutputs",
            ],
            "properties": {
                "requestId": _ref("Uuid"),
                "noteId": _ref("NoteId"),
                "userMessageId": _ref("Uuid"),
                "assistantMessageId": _ref("Uuid"),
                "status": {"const": "PENDING"},
                "requestedTasks": {**string_array, "items": _ref("RequestedTask")},
                "requestedOutputs": {**string_array, "items": {"const": "text"}},
            },
        },
        "ResponsePageStartedPayload": {
            "type": "object",
            "additionalProperties": True,
            "required": [
                "assistantMessageId",
                "responsePageId",
                "presentation",
                "pageIndex",
            ],
            "properties": {
                "assistantMessageId": _ref("Uuid"),
                "responsePageId": {"type": "string", "minLength": 1, "maxLength": 128},
                "presentation": _terminal("PagePresentation"),
                "title": _nullable(
                    {"type": "string", "minLength": 1, "maxLength": 300}
                ),
                "pageIndex": {"type": "integer", "minimum": 0, "maximum": 19},
            },
        },
        "ResponsePageCompletedPayload": {
            "type": "object",
            "additionalProperties": True,
            "required": ["assistantMessageId", "responsePageId", "pageIndex"],
            "properties": {
                "assistantMessageId": _ref("Uuid"),
                "responsePageId": {"type": "string", "minLength": 1, "maxLength": 128},
                "pageIndex": {"type": "integer", "minimum": 0, "maximum": 19},
            },
        },
        "RunCancelingPayload": {
            "type": "object",
            "additionalProperties": True,
            "required": ["assistantMessageId", "status"],
            "properties": {
                "assistantMessageId": _ref("Uuid"),
                "status": {"const": "CANCELING"},
            },
        },
        "RunCanceledPayload": {
            "type": "object",
            "additionalProperties": True,
            "required": ["assistantMessageId", "status", "cause", "message"],
            "properties": {
                "assistantMessageId": _ref("Uuid"),
                "status": {"const": "CANCELED"},
                "cause": _ref("CancelCause"),
                "message": {"type": "string"},
            },
            "allOf": [
                {
                    "if": {"properties": {"cause": {"const": cause}}},
                    "then": {"properties": {"message": {"const": message}}},
                }
                for cause, message in CANCEL_MESSAGES.items()
            ],
        },
        "RunCreatedFrame": _frame("run.created", "RunCreatedPayload"),
        "ResponsePageStartedFrame": _frame(
            "response.page.started", "ResponsePageStartedPayload"
        ),
        "ResponsePageCompletedFrame": _frame(
            "response.page.completed", "ResponsePageCompletedPayload"
        ),
        "RunCancelingFrame": _frame("run.canceling", "RunCancelingPayload"),
        "RunCanceledFrame": _frame("run.canceled", "RunCanceledPayload"),
        "ServerOwnedFrame": {
            "oneOf": [
                _ref("RunCreatedFrame"),
                _ref("ResponsePageStartedFrame"),
                _ref("ResponsePageCompletedFrame"),
                _ref("RunCancelingFrame"),
                _ref("RunCanceledFrame"),
            ]
        },
        "BrowserFrame": {
            "description": "Any durable browser frame: Server-owned types here, "
            "activity/run.completed/run.failed from terminal-v2 browser schema. "
            "Internal fields are forbidden on every frame and payload.",
            "allOf": [
                _ref("NoInternalKeys"),
                {"properties": {"payload": _ref("NoInternalKeys")}},
                {"oneOf": [_ref("ServerOwnedFrame"), {"$ref": TERMINAL_REF}]},
                {
                    "if": {
                        "required": ["type"],
                        "properties": {"type": {"const": "run.completed"}},
                    },
                    "then": {"properties": {"payload": _ref("CompletionExtension")}},
                },
                {
                    "if": {
                        "required": ["type", "payload"],
                        "properties": {
                            "type": {"enum": list(ACTIVITY_TYPES)},
                            "payload": {
                                "required": ["kind"],
                                "properties": {"kind": {"const": "read_back"}},
                            },
                        },
                    },
                    "then": {"properties": {"payload": _ref("ReadBackGuard")}},
                },
            ],
        },
        **_phase2_defs(),
    }
    defs["NoInternalKeys"] = {
        "description": "Internal run keys never appear on a public frame, "
        "payload or Server-owned response object.",
        **_forbid(FORBIDDEN_PUBLIC_KEYS),
    }
    for name in PUBLIC_RESPONSE_OBJECTS:
        defs[name].setdefault("allOf", []).insert(0, _ref("NoInternalKeys"))
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": SCHEMA_ID,
        "title": "browser-run-v1",
        "description": "Browser run API wire contract (meaning owned by "
        "Proovy-server, task 1.75). Validate with a registry that maps "
        f"{TERMINAL_SCHEMA_ID} to terminal-v2/browser.schema.json.",
        "$defs": defs,
    }


# --------------------------------------------------------------------------
# requests and fingerprints
# --------------------------------------------------------------------------


def normalized_request(request: dict[str, Any]) -> dict[str, Any]:
    """The semantic document compared for request-ID reuse (requestId dropped)."""

    document: dict[str, Any] = {}
    if request.get("noteId") is not None:
        document["noteId"] = request["noteId"]
    document["text"] = request["text"].strip()
    if request.get("latex") is not None:
        document["latex"] = request["latex"].strip()
    for key in ("requestedTasks", "requestedOutputs", "turnAttachmentIds"):
        values = sorted(request.get(key) or [])
        if values:
            document[key] = values
    focus = request.get("viewerFocus")
    if focus is not None:
        document["viewerFocus"] = {
            key: value for key, value in focus.items() if value is not None
        }
    return document


def fingerprint(request: dict[str, Any]) -> tuple[str, str]:
    canonical = canonical_json_dumps(normalized_request(request))
    return canonical, hashlib.sha256(canonical.encode()).hexdigest()


FULL_REQUEST = {
    "requestId": REQUEST_ID,
    "noteId": NOTE_ID,
    "text": "이 문제를 풀고 검산해줘",
    "latex": "\\int_0^1 x^2\\,dx",
    "requestedTasks": ["solve", "check"],
    "requestedOutputs": ["text"],
    "turnAttachmentIds": [ATTACHMENT_ID],
    "viewerFocus": {
        "noteAssetId": NOTE_ASSET_ID,
        "page": 3,
        "selection": {"left": 0.1, "top": 0.2, "right": 0.6, "bottom": 0.5},
    },
}
MINIMAL_REQUEST = {"requestId": REQUEST_ID, "text": "이 문제를 풀어줘"}


def _with(base: dict[str, Any], **changes: Any) -> dict[str, Any]:
    value = deepcopy(base)
    for key, item in changes.items():
        if item is _DROP:
            value.pop(key, None)
        else:
            value[key] = item
    return value


_DROP = object()


def valid_requests() -> dict[str, dict[str, Any]]:
    cases = {
        "first-no-note": (
            "noteId 없는 홈 첫 질문 — Server가 Note를 자동 생성한다.",
            MINIMAL_REQUEST,
        ),
        "existing-note-full": ("모든 선택 필드를 채운 기존 Note 요청.", FULL_REQUEST),
        "empty-arrays": (
            "빈 requestedTasks/Outputs·turnAttachmentIds — Brain이 문장에서 추론한다.",
            _with(
                MINIMAL_REQUEST,
                noteId=NOTE_ID,
                requestedTasks=[],
                requestedOutputs=[],
                turnAttachmentIds=[],
            ),
        ),
        "viewer-focus-no-selection": (
            "selection 없는 Viewer Focus(page 전체).",
            _with(
                MINIMAL_REQUEST,
                noteId=NOTE_ID,
                viewerFocus={
                    "noteAssetId": NOTE_ASSET_ID,
                    "page": 1,
                    "selection": None,
                },
            ),
        ),
        "natural-language-variant": (
            "자연어 미지원 의도(변형 문제 생성)는 wire 오류가 아니다 — 접수되어 "
            "unsupported_only로 COMPLETED 된다(streams/unsupported-only-completed).",
            _with(MINIMAL_REQUEST, text="이 문제와 비슷한 변형 문제를 만들어줘"),
        ),
        "text-at-limit": (
            f"text {TEXT_MAX} code point 경계값.",
            _with(MINIMAL_REQUEST, text="가" * TEXT_MAX),
        ),
        "text-at-limit-astral": (
            f"astral 문자(U+1D465) {TEXT_MAX}개 — 길이는 UTF-16 단위가 아니라 code point.",
            _with(MINIMAL_REQUEST, text=ASTRAL * TEXT_MAX),
        ),
        "text-at-limit-surrounded-by-blanks": (
            f"strip 후 {TEXT_MAX} code point — 상한은 strip 후 길이로 판정한다"
            "(원문은 상한 초과).",
            _with(MINIMAL_REQUEST, text="\u3000 " + "가" * TEXT_MAX + " \u00a0"),
        ),
        "latex-at-limit-surrounded-by-blanks": (
            f"latex도 strip 후 {LATEX_MAX} code point로 판정한다.",
            _with(MINIMAL_REQUEST, latex="\n" + "x" * LATEX_MAX + "\n"),
        ),
        "note-id-long-max": (
            "noteId 상한 = Java Long.MAX_VALUE(BIGINT).",
            _with(MINIMAL_REQUEST, noteId=str(MAX_NOTE_ID)),
        ),
    }
    result = {}
    for name, (rationale, request) in cases.items():
        canonical, digest = fingerprint(request)
        result[name] = {
            "rationale": rationale,
            "request": request,
            "expected_canonical": canonical,
            "expected_fingerprint": digest,
        }
    return result


def invalid_requests() -> dict[str, dict[str, Any]]:
    selection = FULL_REQUEST["viewerFocus"]
    cases: dict[str, tuple[str, dict[str, Any], bool, str]] = {
        "missing-request-id": (
            "requestId는 body 필수.",
            _with(MINIMAL_REQUEST, requestId=_DROP),
            True,
            "RUN4001",
        ),
        "uppercase-request-id": (
            "requestId는 소문자 canonical UUID만 허용.",
            _with(MINIMAL_REQUEST, requestId=REQUEST_ID.upper()),
            True,
            "RUN4001",
        ),
        "non-uuid-request-id": (
            "UUID가 아닌 requestId.",
            _with(MINIMAL_REQUEST, requestId="req-1"),
            True,
            "RUN4001",
        ),
        "note-id-number": (
            "noteId는 JSON number가 아니라 10진 문자열.",
            _with(MINIMAL_REQUEST, noteId=123),
            True,
            "RUN4001",
        ),
        "note-id-leading-zero": (
            "noteId 선행 0 금지.",
            _with(MINIMAL_REQUEST, noteId="0123"),
            True,
            "RUN4001",
        ),
        "missing-text": (
            "text 필수.",
            _with(MINIMAL_REQUEST, text=_DROP),
            True,
            "RUN4001",
        ),
        "blank-text": (
            "strip 집합 공백만 있는 text.",
            _with(MINIMAL_REQUEST, text=" \t 　 "),
            True,
            "RUN4001",
        ),
        "text-too-long": (
            f"text strip 후 {TEXT_MAX + 1} code point — schema는 통과, 계약 검사(RUN4001).",
            _with(MINIMAL_REQUEST, text="가" * (TEXT_MAX + 1)),
            False,
            "RUN4001",
        ),
        "text-too-long-astral": (
            f"astral 문자 {TEXT_MAX + 1}개 — UTF-16 길이로 세면 잘못 판정한다.",
            _with(MINIMAL_REQUEST, text=ASTRAL * (TEXT_MAX + 1)),
            False,
            "RUN4001",
        ),
        "request-id-trailing-newline": (
            "requestId 끝 줄바꿈 — 패턴은 입력 끝까지 고정한다($의 줄바꿈 허용 금지).",
            _with(MINIMAL_REQUEST, requestId=REQUEST_ID + "\n"),
            True,
            "RUN4001",
        ),
        "note-id-trailing-newline": (
            "noteId 끝 줄바꿈.",
            _with(MINIMAL_REQUEST, noteId=NOTE_ID + "\n"),
            True,
            "RUN4001",
        ),
        "note-id-over-long-max": (
            "noteId가 Java Long.MAX_VALUE 초과 — 404가 아니라 형식 오류.",
            _with(MINIMAL_REQUEST, noteId=str(MAX_NOTE_ID + 1)),
            True,
            "RUN4001",
        ),
        "note-asset-id-trailing-newline": (
            "viewerFocus.noteAssetId 끝 줄바꿈.",
            _with(
                MINIMAL_REQUEST,
                viewerFocus={"noteAssetId": NOTE_ASSET_ID + "\n", "page": 1},
            ),
            True,
            "RUN4001",
        ),
        "empty-latex": (
            "latex는 있으면 비어 있을 수 없다(null은 부재와 같음).",
            _with(MINIMAL_REQUEST, latex=""),
            True,
            "RUN4001",
        ),
        "latex-too-long": (
            f"latex strip 후 {LATEX_MAX + 1} code point — schema는 통과, 계약 검사(RUN4001).",
            _with(MINIMAL_REQUEST, latex="x" * (LATEX_MAX + 1)),
            False,
            "RUN4001",
        ),
        "unknown-field": (
            "요청은 closed — 알 수 없는 필드.",
            _with(MINIMAL_REQUEST, isStream=True),
            True,
            "RUN4001",
        ),
        "duplicate-task": (
            "requestedTasks 중복.",
            _with(MINIMAL_REQUEST, requestedTasks=["solve", "solve"]),
            True,
            "RUN4001",
        ),
        "unknown-task-variant": (
            "variant는 v1 wire enum이 아니다(자연어 의도와 구분).",
            _with(MINIMAL_REQUEST, requestedTasks=["variant"]),
            True,
            "RUN4001",
        ),
        "attachment-not-uuid": (
            "turnAttachmentIds 항목은 UUID.",
            _with(MINIMAL_REQUEST, turnAttachmentIds=["att-1"]),
            True,
            "RUN4001",
        ),
        "too-many-attachments-wire": (
            f"wire 상한 {ATTACHMENT_WIRE_MAX}개 초과(policy 한도와 별개).",
            _with(
                MINIMAL_REQUEST,
                turnAttachmentIds=[
                    fixed_uuid(f"attachment/many/{index}")
                    for index in range(ATTACHMENT_WIRE_MAX + 1)
                ],
            ),
            True,
            "RUN4001",
        ),
        "viewer-page-zero": (
            "page는 1-based.",
            _with(
                MINIMAL_REQUEST, viewerFocus={"noteAssetId": NOTE_ASSET_ID, "page": 0}
            ),
            True,
            "RUN4001",
        ),
        "viewer-focus-missing-page": (
            "viewerFocus.page 필수.",
            _with(MINIMAL_REQUEST, viewerFocus={"noteAssetId": NOTE_ASSET_ID}),
            True,
            "RUN4001",
        ),
        "selection-out-of-range": (
            "selection 좌표는 [0,1].",
            _with(
                MINIMAL_REQUEST,
                viewerFocus={
                    **selection,
                    "selection": {"left": 0.1, "top": 0.2, "right": 1.2, "bottom": 0.5},
                },
            ),
            True,
            "RUN4001",
        ),
        "selection-inverted": (
            "left<right 위반 — schema는 통과하지만 계약 검사(RUN4001)가 거부한다.",
            _with(
                MINIMAL_REQUEST,
                viewerFocus={
                    **selection,
                    "selection": {"left": 0.6, "top": 0.2, "right": 0.1, "bottom": 0.5},
                },
            ),
            False,
            "RUN4001",
        ),
        "video-output": (
            "video는 알려진 값이지만 Phase 1 미지원 — schema는 통과, RUN4002.",
            _with(MINIMAL_REQUEST, requestedOutputs=["text", "video"]),
            False,
            "RUN4002",
        ),
    }
    for key in FORBIDDEN_REQUEST_KEYS:
        slug = "".join("-" + c.lower() if c.isupper() else c for c in key)
        cases[f"forbidden-{slug}"] = (
            f"브라우저는 {key}를 결정·전달하지 않는다.",
            _with(MINIMAL_REQUEST, **{key: "x"}),
            True,
            "RUN4001",
        )
    return {
        name: {
            "rationale": rationale,
            "request": request,
            "schema_rejects": schema_rejects,
            "expected": {"http": ERRORS[code][0], "code": code},
        }
        for name, (rationale, request, schema_rejects, code) in sorted(cases.items())
    }


def fingerprint_cases() -> dict[str, dict[str, Any]]:
    minimal = _with(MINIMAL_REQUEST, noteId=NOTE_ID)
    pairs = {
        "same-other-request-id": (
            "same",
            "requestId는 의미 문서에서 제외된다.",
            FULL_REQUEST,
            _with(FULL_REQUEST, requestId=OTHER_REQUEST_ID),
        ),
        "same-surrounding-whitespace": (
            "text·latex 양끝 공백은 strip된다.",
            FULL_REQUEST,
            _with(
                FULL_REQUEST,
                text="  이 문제를 풀고 검산해줘　",
                latex="\\int_0^1 x^2\\,dx\n",
            ),
        ),
        "same-non-java-whitespace": (
            "same",
            "U+00A0·U+0085·U+202F 양끝 공백도 strip된다 — Java String.strip()은 "
            "U+00A0·U+202F를 남기므로 strip 집합을 직접 구현해야 한다.",
            FULL_REQUEST,
            _with(
                FULL_REQUEST,
                text="\u00a0이 문제를 풀고 검산해줘\u0085\u202f",
                latex="\u202f\\int_0^1 x^2\\,dx\u00a0",
            ),
        ),
        "different-inner-whitespace": (
            "different",
            "strip은 양끝만 — 내부 공백은 의미 차이다.",
            FULL_REQUEST,
            _with(FULL_REQUEST, text="이 문제를  풀고 검산해줘"),
        ),
        "same-array-order": (
            "same",
            "비순서 배열은 정렬된다.",
            FULL_REQUEST,
            _with(
                FULL_REQUEST,
                requestedTasks=["check", "solve"],
                turnAttachmentIds=[ATTACHMENT_ID],
            ),
        ),
        "same-empty-array-vs-absent": (
            "same",
            "빈 배열과 부재는 같다.",
            minimal,
            _with(
                minimal, requestedTasks=[], requestedOutputs=[], turnAttachmentIds=[]
            ),
        ),
        "same-null-vs-absent": (
            "same",
            "null과 부재는 같다.",
            minimal,
            _with(minimal, latex=None, viewerFocus=None),
        ),
        "same-null-selection": (
            "same",
            "selection null과 부재는 같다.",
            _with(minimal, viewerFocus={"noteAssetId": NOTE_ASSET_ID, "page": 2}),
            _with(
                minimal,
                viewerFocus={
                    "noteAssetId": NOTE_ASSET_ID,
                    "page": 2,
                    "selection": None,
                },
            ),
        ),
        "different-text": (
            "different",
            "text 변경.",
            FULL_REQUEST,
            _with(FULL_REQUEST, text="이 문제를 설명해줘"),
        ),
        "different-latex": (
            "different",
            "latex 변경.",
            FULL_REQUEST,
            _with(FULL_REQUEST, latex="x^2"),
        ),
        "different-tasks": (
            "different",
            "requestedTasks 변경.",
            FULL_REQUEST,
            _with(FULL_REQUEST, requestedTasks=["solve"]),
        ),
        "different-outputs-explicit-vs-inferred": (
            "different",
            "['text'] 명시와 [](추론)은 다른 의미다 — fingerprint 전에 기본값을 "
            "채우지 않는다.",
            FULL_REQUEST,
            _with(FULL_REQUEST, requestedOutputs=[]),
        ),
        "different-attachment": (
            "different",
            "첨부 변경.",
            FULL_REQUEST,
            _with(FULL_REQUEST, turnAttachmentIds=[ATTACHMENT_ID, ATTACHMENT_ID_2]),
        ),
        "different-viewer-page": (
            "different",
            "Viewer Focus page 변경.",
            FULL_REQUEST,
            _with(FULL_REQUEST, viewerFocus={**FULL_REQUEST["viewerFocus"], "page": 4}),
        ),
        "different-selection": (
            "different",
            "selection 변경.",
            FULL_REQUEST,
            _with(
                FULL_REQUEST,
                viewerFocus={
                    **FULL_REQUEST["viewerFocus"],
                    "selection": {"left": 0.1, "top": 0.2, "right": 0.7, "bottom": 0.5},
                },
            ),
        ),
        "different-note-id-added": (
            "different",
            "noteId 부재와 존재는 다르다 — 받은 run.created.noteId를 넣어 재전송하면 RUN4092.",
            MINIMAL_REQUEST,
            _with(MINIMAL_REQUEST, noteId=NOTE_ID),
        ),
    }
    result = {}
    for name, spec in pairs.items():
        if len(spec) == 3:
            rationale, left, right = spec
            relation = "same"
        else:
            relation, rationale, left, right = spec
        left_canonical, left_digest = fingerprint(left)
        right_canonical, right_digest = fingerprint(right)
        assert (left_digest == right_digest) == (relation == "same"), name
        result[name] = {
            "relation": relation,
            "rationale": rationale,
            "left": left,
            "right": right,
            "expected_canonical_left": left_canonical,
            "expected_canonical_right": right_canonical,
            "expected_fingerprint_left": left_digest,
            "expected_fingerprint_right": right_digest,
        }
    return result


# --------------------------------------------------------------------------
# admission decisions
# --------------------------------------------------------------------------


def _state(**overrides: Any) -> dict[str, Any]:
    state = {
        "authenticated": True,
        "request": "valid",
        "existing": None,
        "note": "owned",
        "references": "ok",
        "activeRun": None,
        "rateLimited": False,
        "balance": "sufficient",
    }
    state.update(overrides)
    return state


def _error(code: str, **extra: Any) -> dict[str, Any]:
    http, _ = ERRORS[code]
    return {
        "http": http,
        "outcome": "error",
        "code": code,
        "sideEffects": "none",
        **extra,
    }


def admission_decisions() -> dict[str, Any]:
    stream_new = {
        "http": 200,
        "outcome": "stream_new",
        "replayFromSeq": 1,
        "sideEffects": "created",
    }
    replay_live = {
        "http": 200,
        "outcome": "stream_replay",
        "replayFromSeq": 1,
        "thenLive": True,
        "sideEffects": "none",
    }
    replay_closed = {
        "http": 200,
        "outcome": "stream_replay",
        "replayFromSeq": 1,
        "thenLive": False,
        "sideEffects": "none",
    }
    same = {"sameUser": True, "sameFingerprint": True}
    cases = [
        (
            "new-accepted",
            "정상 신규 접수 — seq 1 run.created부터.",
            _state(),
            stream_new,
        ),
        (
            "new-without-note",
            "noteId 없는 첫 요청 — Note 자동 생성.",
            _state(note="absent"),
            stream_new,
        ),
        (
            "duplicate-same-user-running",
            "같은 사용자·같은 의미 재전송 — 기존 run replay 후 live 합류.",
            _state(existing={**same, "runStatus": "RUNNING"}),
            replay_live,
        ),
        (
            "duplicate-same-user-completed",
            "terminal run 재전송 — terminal까지 replay 후 종료.",
            _state(existing={**same, "runStatus": "COMPLETED"}),
            replay_closed,
        ),
        (
            "duplicate-different-meaning",
            "같은 사용자, 다른 의미의 requestId 재사용.",
            _state(
                existing={
                    "sameUser": True,
                    "sameFingerprint": False,
                    "runStatus": "COMPLETED",
                }
            ),
            _error("RUN4092"),
        ),
        (
            "duplicate-other-user",
            "다른 사용자의 requestId — 상태·runId·noteId 비노출, 다른 의미와 동일 body.",
            _state(
                existing={
                    "sameUser": False,
                    "sameFingerprint": True,
                    "runStatus": "RUNNING",
                }
            ),
            _error("RUN4092"),
        ),
        (
            "duplicate-with-note-id-added",
            "run.created.noteId를 추가해 재전송 — 다른 의미.",
            _state(
                existing={
                    "sameUser": True,
                    "sameFingerprint": False,
                    "runStatus": "RUNNING",
                }
            ),
            _error("RUN4092"),
        ),
        (
            "duplicate-wins-over-active-run",
            "이미 접수된 요청의 재전송은 자기 자신 때문에 409 Active Run을 받지 않는다.",
            _state(
                existing={**same, "runStatus": "PENDING"},
                activeRun={"runId": RUN_ID, "status": "PENDING"},
            ),
            replay_live,
        ),
        (
            "duplicate-not-rate-limited",
            "재조회는 rate limit·잔액 대상이 아니다.",
            _state(
                existing={**same, "runStatus": "COMPLETED"},
                rateLimited=True,
                balance="insufficient",
            ),
            replay_closed,
        ),
        (
            "active-run-conflict",
            "같은 Note에 RUNNING run — activeRunId가 담긴 409.",
            _state(activeRun={"runId": ACTIVE_RUN_ID, "status": "RUNNING"}),
            _error("RUN4091", result={"activeRunId": ACTIVE_RUN_ID}),
        ),
        (
            "active-run-canceling-conflict",
            "CANCELING도 Active Run이다.",
            _state(activeRun={"runId": ACTIVE_RUN_ID, "status": "CANCELING"}),
            _error("RUN4091", result={"activeRunId": ACTIVE_RUN_ID}),
        ),
        ("note-missing", "없는 Note.", _state(note="missing"), _error("RUN4041")),
        (
            "note-other-user",
            "타인 Note — 존재 비노출.",
            _state(note="other_user"),
            _error("RUN4041"),
        ),
        ("note-deleting", "삭제 중 Note.", _state(note="deleting"), _error("RUN4041")),
        (
            "note-check-before-active-run",
            "Note 권한 검사가 Active Run보다 먼저 — 타인 Note의 activeRunId를 노출하지 않는다.",
            _state(
                note="other_user",
                activeRun={"runId": ACTIVE_RUN_ID, "status": "RUNNING"},
            ),
            _error("RUN4041"),
        ),
        (
            "attachment-other-note",
            "다른 Note의 첨부.",
            _state(references="invalid"),
            _error("RUN4221"),
        ),
        (
            "attachment-policy-exceeded",
            "policy 개수/용량 한도 초과.",
            _state(references="policy_exceeded"),
            _error("RUN4221"),
        ),
        (
            "rate-limited",
            "사용자별 반복 요청 제한.",
            _state(rateLimited=True),
            _error("RUN4291", headers={"Retry-After": "30"}),
        ),
        (
            "active-run-before-rate-limit",
            "Active Run 409가 rate limit보다 먼저(상태 복구에 필요).",
            _state(
                activeRun={"runId": ACTIVE_RUN_ID, "status": "RUNNING"},
                rateLimited=True,
            ),
            _error("RUN4091", result={"activeRunId": ACTIVE_RUN_ID}),
        ),
        (
            "insufficient-balance",
            "잔액 부족.",
            _state(balance="insufficient"),
            _error("RUN4021"),
        ),
        (
            "schema-invalid",
            "구조 위반은 requestId 조회보다 먼저.",
            _state(request="schema_invalid", existing={**same, "runStatus": "RUNNING"}),
            _error("RUN4001"),
        ),
        (
            "unsupported-output",
            "video output.",
            _state(request="unsupported"),
            _error("RUN4002"),
        ),
        (
            "unauthenticated",
            "인증 실패가 가장 먼저.",
            _state(authenticated=False, request="schema_invalid"),
            {
                "http": 401,
                "outcome": "error",
                "code": "AUTH4010",
                "sideEffects": "none",
            },
        ),
    ]
    return {
        "precedence": [
            "authenticated",
            "request:schema_invalid",
            "request:unsupported",
            "existing:same_user_same_fingerprint",
            "existing:other",
            "note",
            "references",
            "activeRun",
            "rateLimited",
            "balance",
            "accept",
        ],
        "cases": [
            {"name": name, "rationale": rationale, "state": state, "expected": expected}
            for name, rationale, state, expected in cases
        ],
    }


# --------------------------------------------------------------------------
# streams
# --------------------------------------------------------------------------


class StreamBuilder:
    def __init__(self, scenario: str = "primary") -> None:
        self.ids = scenario_ids(scenario)
        self.run_id = self.ids["runId"]
        self.frames: list[dict[str, Any]] = []
        self.revisions: dict[str, int] = {}

    def _next(self) -> tuple[int, str]:
        seq = len(self.frames) + 1
        return seq, iso(START + timedelta(seconds=seq - 1))

    def server(self, event_type: str, payload: dict[str, Any]) -> StreamBuilder:
        seq, occurred = self._next()
        self.frames.append(
            {
                "contractVersion": FRAME_CONTRACT_VERSION,
                "runId": self.run_id,
                "seq": seq,
                "occurredAt": occurred,
                "type": event_type,
                "payload": payload,
            }
        )
        return self

    def created(self, tasks: list[str] | None = None) -> StreamBuilder:
        return self.server(
            "run.created",
            {
                "requestId": self.ids["requestId"],
                "noteId": NOTE_ID,
                "userMessageId": self.ids["userMessageId"],
                "assistantMessageId": self.ids["assistantMessageId"],
                "status": "PENDING",
                "requestedTasks": tasks if tasks is not None else ["solve"],
                "requestedOutputs": ["text"],
            },
        )

    def activity(self, source: dict[str, Any]) -> StreamBuilder:
        """Append an activity frame produced by Brain's own browser serializer."""

        seq, occurred = self._next()
        activity_id = source["payload"]["activity_id"]
        revision = self.revisions.get(activity_id, 0)
        self.revisions[activity_id] = revision + 1
        event = deepcopy(source)
        event.update(
            run_id=self.run_id,
            occurred_at=occurred,
            source_event_id=derive_source_event_id(
                self.run_id, activity_id, revision, SourceEventType(event["type"])
            ),
        )
        browser = to_browser_event_payload(parse_source_event(event))
        self.frames.append({**browser, "seq": seq})
        return self

    def terminal_pair(
        self, name: str, extension: dict[str, Any] | None = None
    ) -> StreamBuilder:
        """Append page boundaries + the terminal-v2 browser frame of ``name``.

        ``extension`` adds the browser-run-fixtures-v1.1 fields
        (``completion_extension`` output) to the run.completed payload; every
        other payload byte stays the terminal-v2 pair's.
        """

        pair = json.loads((TERMINAL / "pairs" / f"{name}.json").read_text())
        browser = deepcopy(pair["browser"])
        payload = browser["payload"]
        payload["assistantMessageId"] = self.ids["assistantMessageId"]
        if extension is not None:
            assert browser["type"] == "run.completed"
            payload.update(deepcopy(extension))
        if browser["type"] == "run.completed":
            pages = payload["assistantResponse"]["responsePages"]
            for index, page in enumerate(pages):
                started = {
                    "assistantMessageId": self.ids["assistantMessageId"],
                    "responsePageId": page["responsePageId"],
                    "presentation": page["presentation"],
                    "pageIndex": index,
                }
                if page.get("title") is not None:
                    started["title"] = page["title"]
                self.server("response.page.started", started)
                self.server(
                    "response.page.completed",
                    {
                        "assistantMessageId": self.ids["assistantMessageId"],
                        "responsePageId": page["responsePageId"],
                        "pageIndex": index,
                    },
                )
        seq, occurred = self._next()
        browser.update(runId=self.run_id, seq=seq, occurredAt=occurred)
        self.frames.append(browser)
        return self

    def canceling(self) -> StreamBuilder:
        return self.server(
            "run.canceling",
            {
                "assistantMessageId": self.ids["assistantMessageId"],
                "status": "CANCELING",
            },
        )

    def canceled(self, cause: str = "user_requested") -> StreamBuilder:
        return self.server(
            "run.canceled",
            {
                "assistantMessageId": self.ids["assistantMessageId"],
                "status": "CANCELED",
                "cause": cause,
                "message": CANCEL_MESSAGES[cause],
            },
        )


def _activity_file(name: str) -> dict[str, Any]:
    return json.loads((ACTIVITY / f"{name}.json").read_text())


def _activity(
    kind: str, activity_id: str, event_type: str, **payload: Any
) -> dict[str, Any]:
    return {
        "contract_version": FRAME_CONTRACT_VERSION,
        "run_id": RUN_ID,
        "attempt": 1,
        "fence_token": "fence-1",
        "source_event_id": "placeholder",
        "occurred_at": iso(START),
        "type": event_type,
        "payload": {"kind": kind, "activity_id": activity_id, **payload},
    }


METHOD_LABEL = _activity_file("verification-updated")["payload"]["method_label"]


def _analysis(builder: StreamBuilder) -> StreamBuilder:
    return (
        builder.activity(_activity_file("input-analysis-started"))
        .activity(_activity_file("input-analysis-updated"))
        .activity(_activity("input_analysis", "activity-1", "activity.completed"))
    )


def _solve(builder: StreamBuilder) -> StreamBuilder:
    return (
        builder.activity(
            _activity(
                "solution_generation",
                "activity-2",
                "activity.started",
                label="drafting_solution",
            )
        )
        .activity(_activity("solution_generation", "activity-2", "activity.completed"))
        .activity(
            _activity(
                "verification",
                "activity-7",
                "activity.started",
                label="verifying_steps",
                method_label=METHOD_LABEL,
            )
        )
        .activity(_activity_file("verification-updated"))
        .activity(_activity_file("verification-completed"))
    )


def _solution(name: str) -> SolutionVerification:
    """A 2.06 solution-verification-v1 valid record (synthetic problems)."""

    return parse_solution_verification(
        json.loads((SOLUTIONS / f"{name}.json").read_text())
    )


def _with_unsafe_description(record: SolutionVerification) -> SolutionVerification:
    """Same record, first step described with inline code — SafeMarkdown rejects
    it, so the boundary sends that one step as ``plain_text``."""

    steps = list(record.steps)
    steps[0] = steps[0].model_copy(
        update={"description": "`x = 4` 를 대입하면 성립한다"}
    )
    return record.model_copy(update={"steps": tuple(steps)})


def _with_falsified_step(record: SolutionVerification) -> SolutionVerification:
    """Same unverified record with its last ✓ claim step marked ✗ — re-validated
    through the 2.06 model, so the ✗ display has a valid example."""

    data = record.model_dump(mode="json")
    step = [s for s in data["steps"] if s["step_kind"] == "claim"][-1]
    step["check_result"] = "falsified"
    return SolutionVerification.model_validate(data)


_VARIANTS = {
    "text-input-short-answer": _with_unsafe_description,
    "disagreement-unverified": _with_falsified_step,
}


def _extension(
    kind: ResponseKind,
    *names: str,
    disclosure: DisclosureScope = DisclosureScope.FULL,
) -> dict[str, Any]:
    records = [_VARIANTS.get(name, lambda r: r)(_solution(name)) for name in names]
    return completion_extension(kind, records, disclosure=disclosure).browser_payload()


def _read_back(builder: StreamBuilder, *names: str) -> StreamBuilder:
    """Read-back as the first progress Activity — what was read, no solution."""

    problems = [
        read_back_problem(_solution(name).problem).model_dump(
            mode="json", exclude_none=True
        )
        for name in names
    ]
    return builder.activity(
        _activity(
            "read_back",
            "activity-9",
            "activity.started",
            label="reading_back_problems",
        )
    ).activity(
        _activity("read_back", "activity-9", "activity.completed", problems=problems)
    )


SSE_NOTE = (
    "event/id 줄은 byte 비교, data 줄은 JSON 의미 비교(키 순서·공백은 구현 자유)."
)


def sse_text(frames: list[dict[str, Any]]) -> str:
    return "".join(
        f"event: {frame['type']}\nid: {frame['runId']}:{frame['seq']}\n"
        f"data: {json.dumps(frame, ensure_ascii=False, separators=(',', ':'))}\n\n"
        for frame in frames
    )


def streams() -> dict[str, dict[str, Any]]:
    specs: dict[str, tuple[str, str, StreamBuilder]] = {
        "substantive-completed": (
            "COMPLETED",
            "substantive 2-page 완료 — page 경계 2쌍 뒤 run.completed(terminal-v2 multi-page).",
            _solve(_analysis(StreamBuilder().created())).terminal_pair("multi-page"),
        ),
        "substantive-with-source": (
            "COMPLETED",
            "source chip이 있는 substantive(terminal-v2 direct-verified, available=true).",
            _solve(_analysis(StreamBuilder("source").created())).terminal_pair(
                "direct-verified"
            ),
        ),
        "clarification-completed": (
            "COMPLETED",
            "clarification_only — 1 page, source 없음, completed(보상은 Server 정책).",
            _analysis(StreamBuilder("clarification").created(tasks=[])).terminal_pair(
                "clarification"
            ),
        ),
        "unsupported-only-completed": (
            "COMPLETED",
            "자연어 변형 요청의 unsupported_only 완료.",
            _analysis(StreamBuilder("unsupported").created(tasks=[])).terminal_pair(
                "unsupported-only"
            ),
        ),
        "failed-verification": (
            "FAILED",
            "verification_failed — page 이벤트·풀이 없음.",
            _analysis(StreamBuilder("failed").created())
            .activity(
                _activity(
                    "solution_generation",
                    "activity-2",
                    "activity.started",
                    label="drafting_solution",
                )
            )
            .activity(_activity_file("solution-generation-updated"))
            .activity(_activity_file("solution-generation-failed"))
            .terminal_pair("failure"),
        ),
        "failed-invalid-attachment": (
            "FAILED",
            "invalid_attachment — reason sub-code만 추가.",
            StreamBuilder("invalid-attachment")
            .created()
            .terminal_pair("failure-invalid-attachment"),
        ),
        "canceled-pending": (
            "CANCELED",
            "PENDING 취소 — run.canceling 없이 run.canceled.",
            StreamBuilder("canceled").created().canceled(),
        ),
        "canceled-running": (
            "CANCELED",
            "RUNNING 취소 — canceling 뒤 진행 Activity만 canceled.",
            _analysis(StreamBuilder("canceled-running").created())
            .activity(
                _activity(
                    "note_retrieval",
                    "activity-8",
                    "activity.started",
                    label="searching_notes",
                )
            )
            .canceling()
            .activity(_activity_file("note-retrieval-canceled"))
            .canceled(),
        ),
        "canceled-asset-deleting": (
            "CANCELED",
            "의존 asset 삭제로 인한 취소 — cause=asset_deleting.",
            _analysis(StreamBuilder("asset-deleting").created())
            .canceling()
            .canceled("asset_deleting"),
        ),
        # --- browser-run-fixtures-v1.1 (task 2.07): Phase 2 additive fields ---
        "verified-multiple-choice-with-read-back": (
            "COMPLETED",
            "[v1.1] Read-back(read_back Activity, 첫 단계) → 객관식 검증됨. "
            "solutionChecks 1건·단계별 ✓, assuranceScope=verified. "
            "assistantResponse 본문은 terminal-v2 direct-verified 재사용(예시).",
            _solve(
                _analysis(
                    _read_back(
                        StreamBuilder("phase2-verified").created(),
                        "multiple-choice-verified",
                    )
                )
            ).terminal_pair(
                "direct-verified",
                _extension(ResponseKind.STANDARD, "multiple-choice-verified"),
            ),
        ),
        "unverified-disagreement": (
            "COMPLETED",
            "[v1.1] 독립 답 불일치 + ✗ 단계(falsified) → 미검증. "
            "crossCheck.independentAnswerAgrees=false, assuranceScope=unverified.",
            _solve(
                _analysis(
                    _read_back(
                        StreamBuilder("phase2-unverified").created(),
                        "disagreement-unverified",
                    )
                )
            ).terminal_pair(
                "inconclusive-unverified",
                _extension(ResponseKind.STANDARD, "disagreement-unverified"),
            ),
        ),
        "mixed-scope-completed": (
            "COMPLETED",
            "[v1.1] 여러 문제: 단답형 검증됨·검산 안 됨 단계가 있는 검증됨·텍스트 입력"
            "(번호 없음)·증명 검증 대상 아님 → not_applicable은 집계에서 빠지므로 전체 "
            "assuranceScope=verified(문제별 배지는 solutionChecks[].assuranceScope).",
            _solve(
                _analysis(
                    _read_back(
                        StreamBuilder("phase2-mixed").created(),
                        "short-answer-verified",
                        "unparseable-step-verified",
                        "text-input-short-answer",
                        "proof-not-applicable",
                    )
                )
            ).terminal_pair(
                "multi-page",
                _extension(
                    ResponseKind.STANDARD,
                    "short-answer-verified",
                    "unparseable-step-verified",
                    "text-input-short-answer",
                    "proof-not-applicable",
                ),
            ),
        ),
        "not-applicable-proof": (
            "COMPLETED",
            "[v1.1] 증명 답(strategy=none) → assuranceScope=not_applicable, "
            "crossCheck=null.",
            _solve(
                _analysis(
                    _read_back(
                        StreamBuilder("phase2-proof").created(),
                        "proof-not-applicable",
                    )
                )
            ).terminal_pair(
                "supporting-logical-move",
                _extension(ResponseKind.STANDARD, "proof-not-applicable"),
            ),
        ),
        "re-explanation-completed": (
            "COMPLETED",
            "[v1.1] 기존 풀이 재설명 — 검증·Read-back 없이 바로 스트리밍. "
            "responseKind=re_explanation, assuranceScope=not_applicable, "
            "solutionChecks=[]. Front는 '추가 설명' 표시.",
            _analysis(
                StreamBuilder("phase2-re-explanation").created(tasks=["explain"])
            ).terminal_pair(
                "explain-existing", _extension(ResponseKind.RE_EXPLANATION)
            ),
        ),
        "hide-answer-completed": (
            "COMPLETED",
            "[v1.1] 정답 숨김 요청(disclosure=hide_answer) → 배지·✓ 표시는 남고 모든 "
            "단계의 description·descriptionFormat·sympyStatement가 null(withheld=true). "
            "assistantResponse 본문은 terminal-v2 hide-answer 재사용(예시).",
            _solve(
                _analysis(
                    _read_back(
                        StreamBuilder("phase2-hide-answer").created(),
                        "multiple-choice-verified",
                    )
                )
            ).terminal_pair(
                "hide-answer",
                _extension(
                    ResponseKind.STANDARD,
                    "multiple-choice-verified",
                    disclosure=DisclosureScope.HIDE_ANSWER,
                ),
            ),
        ),
        "hide-solution-completed": (
            "COMPLETED",
            "[v1.1] 풀이 숨김 요청(disclosure=hide_solution) → solutionChecks에 배지·"
            "crossCheck만 남고 checkableSteps=[]. assistantResponse 본문은 "
            "terminal-v2 hide-answer 재사용(예시).",
            _solve(
                _analysis(
                    _read_back(
                        StreamBuilder("phase2-hide-solution").created(),
                        "short-answer-verified",
                    )
                )
            ).terminal_pair(
                "hide-answer",
                _extension(
                    ResponseKind.STANDARD,
                    "short-answer-verified",
                    disclosure=DisclosureScope.HIDE_SOLUTION,
                ),
            ),
        ),
    }
    result = {}
    for name, (final_status, rationale, builder) in specs.items():
        result[name] = {
            "rationale": rationale,
            "finalStatus": final_status,
            "runId": builder.run_id,
            "frames": builder.frames,
            "sse": sse_text(builder.frames),
            "sseNote": SSE_NOTE,
        }
    live = result["substantive-with-source"]["frames"]
    replay = deepcopy(live)
    for reference in replay[-1]["payload"]["assistantResponse"]["sourceReferences"]:
        reference["available"] = False
    result["replay-source-unavailable"] = {
        "rationale": "같은 run을 asset 삭제 후 replay — read-time available만 다르고 "
        "id·seq·label snapshot·plainText는 같다.",
        "finalStatus": "COMPLETED",
        "runId": result["substantive-with-source"]["runId"],
        "frames": replay,
        "live": live,
        "allowedDifference": "payload.assistantResponse.sourceReferences[].available",
        "sse": sse_text(replay),
        "sseNote": SSE_NOTE,
    }
    return result


def invalid_streams(valid: dict[str, dict[str, Any]]) -> dict[str, dict[str, Any]]:
    completed = valid["substantive-completed"]["frames"]
    canceled = valid["canceled-running"]["frames"]
    failed = valid["failed-verification"]["frames"]

    def mutate(frames: list[dict[str, Any]], change) -> list[dict[str, Any]]:
        value = deepcopy(frames)
        change(value)
        return value

    def renumber(frames: list[dict[str, Any]]) -> None:
        for index, frame in enumerate(frames, start=1):
            frame["seq"] = index

    def drop_first_page_completed(frames: list[dict[str, Any]]) -> None:
        index = next(
            i for i, f in enumerate(frames) if f["type"] == "response.page.completed"
        )
        del frames[index]
        renumber(frames)

    def page_in_failed(frames: list[dict[str, Any]]) -> None:
        frames.insert(
            len(frames) - 1,
            {
                **deepcopy(completed[-5]),
                "runId": frames[0]["runId"],
            },
        )
        renumber(frames)

    def page_after_canceling(frames: list[dict[str, Any]]) -> None:
        frames.insert(len(frames) - 1, deepcopy(completed[-5]))
        renumber(frames)

    def swap_pages(frames: list[dict[str, Any]]) -> None:
        pages = [
            i for i, f in enumerate(frames) if f["type"].startswith("response.page.")
        ]
        first, second = pages[0:2], pages[2:4]
        block_a = [frames[i] for i in first]
        block_b = [frames[i] for i in second]
        for i, frame in zip(first + second, block_b + block_a, strict=True):
            frames[i] = deepcopy(frame)
        renumber(frames)

    def activity_without_start(frames: list[dict[str, Any]]) -> None:
        index = next(i for i, f in enumerate(frames) if f["type"] == "activity.started")
        del frames[index]
        renumber(frames)

    cases: dict[str, tuple[str, str, list[dict[str, Any]], bool]] = {
        "seq-gap": (
            "S1",
            "seq 빈틈.",
            mutate(completed, lambda f: f[3].update(seq=40)),
            False,
        ),
        "first-not-run-created": (
            "S1",
            "첫 frame이 run.created가 아님.",
            mutate(completed, lambda f: (f.pop(0), renumber(f))),
            False,
        ),
        "two-terminals": (
            "S2",
            "terminal 두 개.",
            mutate(
                completed,
                lambda f: (
                    f.append({**deepcopy(failed[-1]), "runId": f[0]["runId"]}),
                    renumber(f),
                ),
            ),
            False,
        ),
        "event-after-terminal": (
            "S2",
            "terminal 뒤 activity.",
            mutate(completed, lambda f: (f.append(deepcopy(f[1])), renumber(f))),
            False,
        ),
        "page-after-canceling": (
            "S3",
            "run.canceling 뒤 page 경계.",
            mutate(canceled, page_after_canceling),
            False,
        ),
        "page-in-failed-run": (
            "S4",
            "failed run에 page 경계.",
            mutate(failed, page_in_failed),
            False,
        ),
        "page-unpaired": (
            "S4",
            "page.started만 있고 completed 없음.",
            mutate(completed, drop_first_page_completed),
            False,
        ),
        "page-order-mismatch": (
            "S4",
            "pageIndex 순서가 run.completed pages와 다름.",
            mutate(completed, swap_pages),
            False,
        ),
        "page-id-not-in-completed": (
            "S4",
            "page 경계 ID가 run.completed에 없음.",
            mutate(
                completed,
                lambda f: f[-3].__setitem__(
                    "payload", {**f[-3]["payload"], "responsePageId": "page-x"}
                ),
            ),
            False,
        ),
        "assistant-message-id-mismatch": (
            "S5",
            "assistantMessageId가 run.created와 다름.",
            mutate(
                completed,
                lambda f: f[-1]["payload"].update(
                    assistantMessageId=fixed_uuid("message/other")
                ),
            ),
            False,
        ),
        "run-id-mismatch": (
            "S5",
            "frame runId가 섞임.",
            mutate(completed, lambda f: f[2].update(runId=OTHER_RUN_ID)),
            False,
        ),
        "activity-without-start": (
            "S6",
            "started 없는 activity 후속 이벤트.",
            mutate(completed, activity_without_start),
            False,
        ),
        "attempt-in-frame": (
            "S7",
            "frame에 attempt.",
            mutate(completed, lambda f: f[1].update(attempt=1)),
            True,
        ),
        "fence-token-in-payload": (
            "S7",
            "payload에 fenceToken.",
            mutate(completed, lambda f: f[1]["payload"].update(fenceToken="fence-1")),
            True,
        ),
        "source-event-id-in-frame": (
            "S7",
            "frame에 sourceEventId.",
            mutate(completed, lambda f: f[1].update(sourceEventId="x")),
            True,
        ),
        "failure-class-in-run-failed": (
            "S7",
            "run.failed에 failureClass.",
            mutate(
                failed, lambda f: f[-1]["payload"].update(failureClass="verification")
            ),
            True,
        ),
        "retrying-status-in-run-created": (
            "S7",
            "내부 RETRYING/비PENDING status 노출.",
            mutate(completed, lambda f: f[0]["payload"].update(status="RETRYING")),
            True,
        ),
        "run-created-missing-note-id": (
            "S7",
            "run.created에 noteId 누락.",
            mutate(completed, lambda f: f[0]["payload"].pop("noteId")),
            True,
        ),
        "cancel-unknown-cause": (
            "S7",
            "허용되지 않은 cancel cause.",
            mutate(canceled, lambda f: f[-1]["payload"].update(cause="disconnect")),
            True,
        ),
        "cancel-message-mismatch": (
            "S7",
            "cause별 고정 message와 다름.",
            mutate(canceled, lambda f: f[-1]["payload"].update(message="중단됨")),
            True,
        ),
        "non-uuid-run-id": (
            "S7",
            "Server 소유 frame runId는 UUID.",
            mutate(canceled, lambda f: [frame.update(runId="run-7") for frame in f]),
            True,
        ),
    }
    cases.update(_phase2_invalid_streams(valid, mutate))
    return {
        name: {
            "rationale": rationale,
            "violates": rule,
            "schema_rejects": schema_rejects,
            "frames": frames,
        }
        for name, (rule, rationale, frames, schema_rejects) in sorted(cases.items())
    }


def _phase2_invalid_streams(
    valid: dict[str, dict[str, Any]], mutate
) -> dict[str, tuple[str, str, list[dict[str, Any]], bool]]:
    """browser-run-fixtures-v1.1 negatives: each breaks one S7 schema rule."""

    verified = valid["verified-multiple-choice-with-read-back"]["frames"]
    unverified = valid["unverified-disagreement"]["frames"]
    mixed = valid["mixed-scope-completed"]["frames"]
    re_explanation = valid["re-explanation-completed"]["frames"]
    hide_answer = valid["hide-answer-completed"]["frames"]
    hide_solution = valid["hide-solution-completed"]["frames"]

    def last(change):
        return lambda frames: change(frames[-1]["payload"])

    def first_check(change):
        return last(lambda payload: change(payload["solutionChecks"][0]))

    def read_back_completed(change):
        def apply(frames: list[dict[str, Any]]) -> None:
            frame = next(
                f
                for f in frames
                if f["type"] == "activity.completed"
                and f["payload"]["kind"] == "read_back"
            )
            change(frame["payload"])

        return apply

    def set_step(index: int, **values: Any):
        return first_check(lambda check: check["checkableSteps"][index].update(values))

    def falsify_claim(check: dict[str, Any]) -> None:
        step = next(s for s in check["checkableSteps"] if s["stepKind"] == "claim")
        step["checkResult"] = "falsified"

    def drop(key: str):
        return last(lambda payload: payload.pop(key))

    return {
        "re-explanation-verified-scope": (
            "S7",
            "재설명은 검증 대상 아님 — assuranceScope=verified 거부.",
            mutate(re_explanation, last(lambda p: p.update(assuranceScope="verified"))),
            True,
        ),
        "re-explanation-with-solution-checks": (
            "S7",
            "재설명은 solutionChecks가 비어 있어야 한다(검증 없이 스트리밍).",
            mutate(
                re_explanation,
                last(
                    lambda p: p.update(
                        solutionChecks=deepcopy(
                            mixed[-1]["payload"]["solutionChecks"][-1:]
                        )
                    )
                ),
            ),
            True,
        ),
        "extension-without-response-kind": (
            "S7",
            "responseKind·assuranceScope·solutionChecks는 함께 오거나 모두 없다.",
            mutate(verified, drop("responseKind")),
            True,
        ),
        "unknown-assurance-scope": (
            "S7",
            "AssuranceScope는 verified·unverified·not_applicable 세 값뿐.",
            mutate(verified, last(lambda p: p.update(assuranceScope="partial"))),
            True,
        ),
        "overall-unverified-without-unverified-solution": (
            "S7",
            "not_applicable은 집계에서 빠진다 — 검증 대상이 모두 verified인데 "
            "전체 unverified 거부.",
            mutate(mixed, last(lambda p: p.update(assuranceScope="unverified"))),
            True,
        ),
        "overall-not-applicable-with-verified": (
            "S7",
            "verified 풀이가 하나라도 있으면(unverified 없이) 전체는 verified — "
            "not_applicable 거부.",
            mutate(mixed, last(lambda p: p.update(assuranceScope="not_applicable"))),
            True,
        ),
        "verified-solution-with-falsified-step": (
            "S7",
            "✗ 단계가 있는 solution은 verified일 수 없다.",
            mutate(verified, first_check(falsify_claim)),
            True,
        ),
        "unverified-without-cause": (
            "S7",
            "불일치·검산기 실패·✗ 단계가 없는데 unverified — 2.06 조건 위반.",
            mutate(
                verified,
                last(
                    lambda p: (
                        p.update(assuranceScope="unverified"),
                        p["solutionChecks"][0].update(assuranceScope="unverified"),
                    )
                ),
            ),
            True,
        ),
        "proof-with-verified-scope": (
            "S7",
            "strategy=none(증명·서술)은 항상 not_applicable.",
            mutate(
                mixed,
                last(
                    lambda p: p["solutionChecks"][-1].update(assuranceScope="verified")
                ),
            ),
            True,
        ),
        "argue-step-with-statement": (
            "S7",
            "argue 단계는 수식이 없고 검산 대상 아님뿐이다.",
            mutate(verified, set_step(0, stepKind="argue")),
            True,
        ),
        "verified-step-without-formula": (
            "S7",
            "✓ 단계는 검산한 수식(claim: lhs·rhs)을 그대로 가진다.",
            mutate(
                unverified,
                first_check(
                    lambda c: next(
                        s
                        for s in c["checkableSteps"]
                        if s["stepKind"] == "claim" and s["checkResult"] == "verified"
                    ).update(sympyStatement={"lhs": "x"})
                ),
            ),
            True,
        ),
        "check-reason-exposed": (
            "S7",
            "검산기 사유 코드(checkReason)는 내부 전용.",
            mutate(verified, set_step(0, checkReason="numeric")),
            True,
        ),
        "independent-answer-exposed": (
            "S7",
            "독립 답 프로그램·값(evidenceCode·independentAnswer)은 내부 전용(미확정 Candidate).",
            mutate(
                unverified,
                first_check(
                    lambda c: c.update(
                        evidenceCode="answer = 7", independentAnswer={"value": "7"}
                    )
                ),
            ),
            True,
        ),
        "hide-answer-step-leak": (
            "S7",
            "정답 숨김(hide_answer)에서 withheld 단계가 수식·설명을 실으면 거부 — "
            "choose 단계의 보기 번호가 정답이다.",
            mutate(
                hide_answer,
                set_step(
                    -1,
                    description="따라서 답은 ③이다.",
                    descriptionFormat="safe_markdown",
                    sympyStatement={"value": "3", "choice": 3},
                ),
            ),
            True,
        ),
        "hide-answer-public-step": (
            "S7",
            "정답 숨김(hide_answer)에서는 모든 단계가 withheld=true다.",
            mutate(
                hide_answer,
                set_step(
                    0,
                    withheld=False,
                    description="조건을 정리한다.",
                    descriptionFormat="safe_markdown",
                ),
            ),
            True,
        ),
        "hide-solution-with-steps": (
            "S7",
            "풀이 숨김(hide_solution)은 단계를 하나도 보내지 않는다.",
            mutate(
                hide_solution,
                first_check(
                    lambda c: c.update(
                        checkableSteps=deepcopy(
                            hide_answer[-1]["payload"]["solutionChecks"][0][
                                "checkableSteps"
                            ]
                        )
                    )
                ),
            ),
            True,
        ),
        "solution-check-without-disclosure": (
            "S7",
            "solution check는 disclosure를 반드시 가진다(없으면 숨김 여부를 알 수 없다).",
            mutate(verified, first_check(lambda c: c.pop("disclosure"))),
            True,
        ),
        "read-back-with-answer": (
            "S7",
            "Read-back은 검증 전이다 — 문제에 답을 붙일 수 없다.",
            mutate(
                verified,
                read_back_completed(
                    lambda p: p["problems"][0].update(answer={"choice": 3})
                ),
            ),
            True,
        ),
        "read-back-with-steps": (
            "S7",
            "Read-back에 검증 전 풀이 단계를 실을 수 없다.",
            mutate(
                verified,
                read_back_completed(
                    lambda p: p.update(
                        checkableSteps=deepcopy(
                            verified[-1]["payload"]["solutionChecks"][0][
                                "checkableSteps"
                            ]
                        )
                    )
                ),
            ),
            True,
        ),
    }


STREAM_RULES = {
    "S1": "seq 1은 run.created이고 seq는 1부터 빈틈없이 +1.",
    "S2": "terminal(run.completed|run.failed|run.canceled)은 정확히 하나이며 마지막 frame.",
    "S3": "run.canceling은 최대 1회이고 run.canceled 앞. 그 뒤에는 activity.completed|failed|canceled와 run.canceled만.",
    "S4": "response.page.*는 completed run에만, 모든 page의 started→completed 쌍이 pageIndex 0..n-1 순서로 run.completed 바로 앞에 연속하고 ID·presentation·title이 run.completed pages와 일치.",
    "S5": "모든 frame runId가 같고 모든 assistantMessageId가 run.created의 값과 같다.",
    "S6": "activity 후속 이벤트는 같은 activityId·kind의 앞선 activity.started를 가리키고 activity terminal 뒤에는 그 activity의 이벤트가 없다.",
    "S7": "각 frame은 BrowserFrame schema를 통과한다(내부 필드 금지·Server 소유 payload).",
}


# --------------------------------------------------------------------------
# cursors
# --------------------------------------------------------------------------


def cursor_cases(valid_streams: dict[str, dict[str, Any]]) -> dict[str, Any]:
    terminal_last = len(valid_streams["substantive-completed"]["frames"])
    active_last = ACTIVE_LAST_SEQ
    ok = lambda seq, live: {"http": 200, "replayFromSeq": seq, "thenLive": live}  # noqa: E731
    err = lambda code: {"http": ERRORS[code][0], "code": code}  # noqa: E731
    cases = [
        ("no-cursor", "terminal", None, None, ok(1, False)),
        ("no-cursor-active", "active", None, None, ok(1, True)),
        ("valid-middle", "terminal", f"{RUN_ID}:3", None, ok(4, False)),
        ("valid-active", "active", f"{ACTIVE_RUN_ID}:2", None, ok(3, True)),
        ("valid-query", "active", None, f"{ACTIVE_RUN_ID}:2", ok(3, True)),
        (
            "header-and-query-equal",
            "active",
            f"{ACTIVE_RUN_ID}:2",
            f"{ACTIVE_RUN_ID}:2",
            ok(3, True),
        ),
        (
            "last-seq-terminal",
            "terminal",
            f"{RUN_ID}:{terminal_last}",
            None,
            {**ok(terminal_last + 1, False), "frameCount": 0},
        ),
        (
            "last-seq-active",
            "active",
            f"{ACTIVE_RUN_ID}:{active_last}",
            None,
            {**ok(active_last + 1, True), "frameCount": 0},
        ),
        ("uppercase-uuid", "terminal", f"{RUN_ID.upper()}:3", None, err("RUN4003")),
        ("seq-zero", "terminal", f"{RUN_ID}:0", None, err("RUN4003")),
        ("seq-leading-zero", "terminal", f"{RUN_ID}:03", None, err("RUN4003")),
        ("seq-negative", "terminal", f"{RUN_ID}:-1", None, err("RUN4003")),
        ("seq-over-2-53", "terminal", f"{RUN_ID}:{MAX_SEQ + 1}", None, err("RUN4003")),
        (
            "seq-16-digit-max",
            "terminal",
            f"{RUN_ID}:9999999999999999",
            None,
            err("RUN4003"),
        ),
        (
            "seq-at-max-beyond-last",
            "terminal",
            f"{RUN_ID}:{MAX_SEQ}",
            None,
            err("RUN4003"),
        ),
        ("trailing-newline", "terminal", f"{RUN_ID}:3\n", None, err("RUN4003")),
        ("no-separator", "terminal", RUN_ID, None, err("RUN4003")),
        ("surrounding-space", "terminal", f" {RUN_ID}:3", None, err("RUN4003")),
        ("seq-only", "terminal", "3", None, err("RUN4003")),
        (
            "beyond-last-seq",
            "terminal",
            f"{RUN_ID}:{terminal_last + 1}",
            None,
            err("RUN4003"),
        ),
        (
            "header-query-mismatch",
            "active",
            f"{ACTIVE_RUN_ID}:2",
            f"{ACTIVE_RUN_ID}:3",
            err("RUN4003"),
        ),
        ("other-run", "terminal", f"{OTHER_RUN_ID}:3", None, err("RUN4004")),
        ("other-run-query", "active", None, f"{OTHER_RUN_ID}:1", err("RUN4004")),
    ]
    access_cases = [
        ("run-missing", "missing", err("RUN4041")),
        ("run-other-user", "other_user", err("RUN4041")),
    ]
    # Cursor values that fail the EventId schema itself (format, not range).
    schema_rejects = {
        "uppercase-uuid",
        "seq-zero",
        "seq-leading-zero",
        "seq-negative",
        "seq-over-2-53",
        "seq-16-digit-max",
        "no-separator",
        "surrounding-space",
        "seq-only",
        "trailing-newline",
    }
    return {
        "runs": {
            "terminal": {
                "runId": RUN_ID,
                "status": "COMPLETED",
                "lastDurableSeq": terminal_last,
                "stream": "substantive-completed",
            },
            "active": {
                "runId": ACTIVE_RUN_ID,
                "status": "RUNNING",
                "lastDurableSeq": active_last,
            },
        },
        "thenLiveRule": "thenLive is true exactly when runs.<run>.status is not "
        "terminal (COMPLETED|FAILED|CANCELED).",
        "cases": [
            {
                "name": name,
                "run": run,
                "lastEventIdHeader": header,
                "lastEventIdQuery": query,
                "schemaRejects": name in schema_rejects,
                "expected": expected,
            }
            for name, run, header, query, expected in cases
        ],
        "accessCases": [
            {
                "name": name,
                "access": access,
                "lastEventIdHeader": f"{RUN_ID}:3",
                "expected": expected,
            }
            for name, access, expected in access_cases
        ],
        "precedence": [
            "authenticated",
            "run access (RUN4041)",
            "cursor format/range (RUN4003)",
            "cursor run (RUN4004)",
        ],
    }


def cancel_cases() -> dict[str, Any]:
    ok = lambda outcome, status: {  # noqa: E731
        "http": 200,
        "fixture": f"responses/valid/cancel-{outcome}",
        "status": status,
    }
    err = lambda code: {"http": ERRORS[code][0], "code": code}  # noqa: E731
    cases = [
        (
            "no-body",
            "body 없음 — 정상 취소.",
            None,
            "running",
            ok("accepted-running", "CANCELING"),
        ),
        (
            "empty-object-body",
            "cancel은 body가 없다 — `{}`도 closed 위반.",
            "{}",
            "running",
            err("RUN4001"),
        ),
        (
            "reason-body",
            "취소 사유 등 어떤 필드도 받지 않는다(무시하지 않는다).",
            '{"reason":"user"}',
            "running",
            err("RUN4001"),
        ),
        (
            "repeat",
            "멱등 — 이미 CANCELING이면 already_canceling.",
            None,
            "canceling",
            ok("already-canceling", "CANCELING"),
        ),
        (
            "terminal",
            "terminal run은 already_terminal.",
            None,
            "completed",
            ok("already-terminal", "COMPLETED"),
        ),
        (
            "pending",
            "PENDING은 즉시 CANCELED(run.canceling 없이 run.canceled).",
            None,
            "pending",
            ok("accepted-pending", "CANCELED"),
        ),
        ("run-missing", "없는 run.", None, "missing", err("RUN4041")),
        (
            "run-other-user",
            "타인 run — 없는 run과 같은 404 body(존재 비노출).",
            None,
            "other_user",
            err("RUN4041"),
        ),
        (
            "body-before-run-lookup",
            "body 구조 검사가 run 조회보다 먼저(admission과 같은 원칙).",
            "{}",
            "missing",
            err("RUN4001"),
        ),
    ]
    return {
        "path": "POST /api/ai-runs/{runId}/cancel",
        "precedence": [
            "authenticated",
            "body absent (RUN4001)",
            "run access (RUN4041)",
            "outcome",
        ],
        "cases": [
            {
                "name": name,
                "rationale": rationale,
                "rawBody": body,
                "run": run,
                "expected": expected,
            }
            for name, rationale, body, run, expected in cases
        ],
    }


def heartbeat_invalid() -> dict[str, Any]:
    cases = [
        (
            "heartbeat-with-id",
            "heartbeat는 id가 없다 — cursor를 전진시키면 안 된다.",
            f": heartbeat\nid: {RUN_ID}:3\n\n",
        ),
        (
            "heartbeat-with-data",
            "heartbeat는 data가 없다 — frame으로 저장·replay되지 않는다.",
            ": heartbeat\ndata: {}\n\n",
        ),
        (
            "heartbeat-as-event",
            "heartbeat는 event가 아니라 comment다.",
            "event: heartbeat\ndata: {}\n\n",
        ),
    ]
    return {
        "valid": HEARTBEAT,
        "cases": [
            {"name": name, "rationale": rationale, "sse": text}
            for name, rationale, text in cases
        ],
    }


# --------------------------------------------------------------------------
# HTTP responses
# --------------------------------------------------------------------------


def _ok(result: dict[str, Any]) -> dict[str, Any]:
    return {
        "isSuccess": True,
        "code": "COMMON200",
        "message": SUCCESS_MESSAGE,
        "result": result,
    }


def _status(
    status: str, last_seq: int, ids: dict[str, str] | None = None, **extra: Any
) -> dict[str, Any]:
    ids = ids or scenario_ids("primary")
    body = {
        "runId": ids["runId"],
        "requestId": ids["requestId"],
        "noteId": NOTE_ID,
        "userMessageId": ids["userMessageId"],
        "assistantMessageId": ids["assistantMessageId"],
        "status": status,
        "cancelable": status in ("PENDING", "RUNNING"),
        "lastSeq": last_seq,
        "responseDisposition": None,
        "failure": None,
        "createdAt": iso(START),
        "completedAt": None,
    }
    body.update(extra)
    return body


def responses(
    valid_streams: dict[str, dict[str, Any]],
) -> tuple[dict[str, Any], dict[str, Any]]:
    done = iso(START + timedelta(seconds=30))
    completed = valid_streams["substantive-with-source"]["frames"][-1]["payload"]
    clarification = valid_streams["clarification-completed"]["frames"][-1]["payload"]
    failed = valid_streams["failed-verification"]["frames"][-1]["payload"]

    def last_seq(stream: str) -> int:
        return len(valid_streams[stream]["frames"])

    def ids(stream: str) -> dict[str, str]:
        frames = valid_streams[stream]["frames"]
        created = frames[0]["payload"]
        return {
            "runId": frames[0]["runId"],
            "requestId": created["requestId"],
            "userMessageId": created["userMessageId"],
            "assistantMessageId": created["assistantMessageId"],
        }

    source = ids("substantive-with-source")
    pending = scenario_ids("pending")
    canceling = scenario_ids("canceling")

    def at(seconds: int) -> str:
        return iso(START + timedelta(seconds=seconds))

    def user_message(
        message_id: str, run_id: str, text: str, created: str
    ) -> dict[str, Any]:
        return {
            "messageId": message_id,
            "role": "user",
            "status": "COMPLETED",
            "runId": run_id,
            "createdAt": created,
            "user": {
                "text": text,
                "latex": None,
                "requestedTasks": ["solve"],
                "requestedOutputs": ["text"],
                "turnAttachments": [
                    {
                        "attachmentId": ATTACHMENT_ID,
                        "mime": "image/png",
                        "displayName": "문제.png",
                    }
                ],
                "viewerFocus": {
                    "noteAssetId": NOTE_ASSET_ID,
                    "page": 3,
                    "selection": None,
                },
            },
        }

    def assistant(
        status: str,
        run_id: str,
        message_id: str,
        created: str = iso(START + timedelta(seconds=1)),
        **content: Any,
    ) -> dict[str, Any]:
        body = {
            "responseDisposition": None,
            "clarificationReason": None,
            "assistantResponse": None,
            "verificationSummary": [],
            "failure": None,
            "cancel": None,
        }
        body.update(content)
        return {
            "messageId": message_id,
            "role": "assistant",
            "status": status,
            "runId": run_id,
            "createdAt": created,
            "assistant": body,
        }

    completed_assistant = assistant(
        "COMPLETED",
        source["runId"],
        source["assistantMessageId"],
        responseDisposition=completed["responseDisposition"],
        clarificationReason=completed.get("clarificationReason"),
        assistantResponse=completed["assistantResponse"],
        verificationSummary=completed.get("verificationSummary", []),
    )
    # Terminal status responses describe the run of a streams/valid fixture:
    # lastSeq is that stream's length (see test_terminal_status_matches_stream).
    status_streams = {
        "status-completed-clarification": "clarification-completed",
        "status-completed-substantive": "substantive-completed",
        "status-failed": "failed-verification",
        "status-failed-invalid-attachment": "failed-invalid-attachment",
        "status-canceled": "canceled-pending",
    }
    valid = {
        "status-pending": _ok(_status("PENDING", 1, pending)),
        "status-running": _ok(_status("RUNNING", ACTIVE_LAST_SEQ, ACTIVE)),
        "status-canceling": _ok(_status("CANCELING", 9, canceling)),
        "status-completed-clarification": _ok(
            _status(
                "COMPLETED",
                last_seq("clarification-completed"),
                ids("clarification-completed"),
                responseDisposition="clarification_only",
                completedAt=done,
            )
        ),
        "status-completed-substantive": _ok(
            _status(
                "COMPLETED",
                last_seq("substantive-completed"),
                ids("substantive-completed"),
                responseDisposition="substantive",
                completedAt=done,
            )
        ),
        "status-failed": _ok(
            _status(
                "FAILED",
                last_seq("failed-verification"),
                ids("failed-verification"),
                failure={
                    "code": failed["code"],
                    "reason": None,
                    "recoverable": failed["recoverable"],
                },
                completedAt=done,
            )
        ),
        "status-failed-invalid-attachment": _ok(
            _status(
                "FAILED",
                last_seq("failed-invalid-attachment"),
                ids("failed-invalid-attachment"),
                failure={
                    "code": "invalid_attachment",
                    "reason": valid_streams["failed-invalid-attachment"]["frames"][-1][
                        "payload"
                    ]["reason"],
                    "recoverable": False,
                },
                completedAt=done,
            )
        ),
        "status-canceled": _ok(
            _status(
                "CANCELED",
                last_seq("canceled-pending"),
                ids("canceled-pending"),
                completedAt=done,
            )
        ),
        "cancel-accepted-pending": _ok(
            {"runId": pending["runId"], "status": "CANCELED", "outcome": "accepted"}
        ),
        "cancel-accepted-running": _ok(
            {"runId": ACTIVE_RUN_ID, "status": "CANCELING", "outcome": "accepted"}
        ),
        "cancel-already-canceling": _ok(
            {
                "runId": canceling["runId"],
                "status": "CANCELING",
                "outcome": "already_canceling",
            }
        ),
        "cancel-already-terminal": _ok(
            {"runId": RUN_ID, "status": "COMPLETED", "outcome": "already_terminal"}
        ),
        "conversation-completed": _ok(
            {
                "noteId": NOTE_ID,
                "activeRun": None,
                "messages": [
                    user_message(
                        source["userMessageId"],
                        source["runId"],
                        "이 문제를 풀어줘",
                        at(0),
                    ),
                    completed_assistant,
                ],
                "nextBefore": None,
            }
        ),
        "conversation-active-run": _ok(
            {
                "noteId": NOTE_ID,
                "activeRun": {
                    "runId": ACTIVE_RUN_ID,
                    "status": "RUNNING",
                    "lastSeq": ACTIVE_LAST_SEQ,
                },
                "messages": [
                    user_message(
                        source["userMessageId"],
                        source["runId"],
                        "이 문제를 풀어줘",
                        at(0),
                    ),
                    completed_assistant,
                    user_message(
                        ACTIVE["userMessageId"],
                        ACTIVE_RUN_ID,
                        "다른 방법으로도 풀어줘",
                        at(60),
                    ),
                    assistant(
                        "STREAMING",
                        ACTIVE_RUN_ID,
                        ACTIVE["assistantMessageId"],
                        at(61),
                    ),
                ],
                "nextBefore": fixed_uuid("message/older"),
            }
        ),
        "conversation-clarification-failed-canceled": _ok(
            {
                "noteId": NOTE_ID,
                "activeRun": None,
                "messages": [
                    user_message(
                        ids("clarification-completed")["userMessageId"],
                        ids("clarification-completed")["runId"],
                        "이거 풀어줘",
                        at(0),
                    ),
                    assistant(
                        "COMPLETED",
                        ids("clarification-completed")["runId"],
                        ids("clarification-completed")["assistantMessageId"],
                        at(1),
                        responseDisposition="clarification_only",
                        clarificationReason=clarification["clarificationReason"],
                        assistantResponse=clarification["assistantResponse"],
                    ),
                    user_message(
                        ids("failed-verification")["userMessageId"],
                        ids("failed-verification")["runId"],
                        "첨부한 문제를 풀어줘",
                        at(60),
                    ),
                    assistant(
                        "FAILED",
                        ids("failed-verification")["runId"],
                        ids("failed-verification")["assistantMessageId"],
                        at(61),
                        failure={
                            "code": failed["code"],
                            "reason": None,
                            "message": failed["message"],
                            "recoverable": failed["recoverable"],
                        },
                    ),
                    user_message(
                        ids("canceled-pending")["userMessageId"],
                        ids("canceled-pending")["runId"],
                        "다시 풀어줘",
                        at(120),
                    ),
                    assistant(
                        "CANCELED",
                        ids("canceled-pending")["runId"],
                        ids("canceled-pending")["assistantMessageId"],
                        at(121),
                        cancel={
                            "cause": "user_requested",
                            "message": CANCEL_MESSAGES["user_requested"],
                        },
                    ),
                ],
                "nextBefore": None,
            }
        ),
    }

    # browser-run-fixtures-v1.1: stored assistant messages carry the same
    # additive fields as their run.completed frame (Server persists and
    # replays them unchanged).
    def phase2_assistant(stream: str, seconds: int) -> dict[str, Any]:
        payload = valid_streams[stream]["frames"][-1]["payload"]
        run = ids(stream)
        return assistant(
            "COMPLETED",
            run["runId"],
            run["assistantMessageId"],
            at(seconds),
            responseDisposition=payload["responseDisposition"],
            clarificationReason=payload.get("clarificationReason"),
            assistantResponse=payload["assistantResponse"],
            verificationSummary=payload.get("verificationSummary", []),
            **{key: payload[key] for key in EXTENSION_KEYS},
        )

    verified_run = ids("verified-multiple-choice-with-read-back")
    re_explanation_run = ids("re-explanation-completed")
    valid["conversation-verified-then-re-explanation"] = _ok(
        {
            "noteId": NOTE_ID,
            "activeRun": None,
            "messages": [
                user_message(
                    verified_run["userMessageId"],
                    verified_run["runId"],
                    "15번 풀어줘",
                    at(200),
                ),
                phase2_assistant("verified-multiple-choice-with-read-back", 201),
                user_message(
                    re_explanation_run["userMessageId"],
                    re_explanation_run["runId"],
                    "방금 풀이 두 번째 단계를 더 쉽게 설명해줘",
                    at(260),
                ),
                phase2_assistant("re-explanation-completed", 261),
            ],
            "nextBefore": None,
        }
    )
    schema_names = {
        "status": "RunStatusResponse",
        "cancel": "RunCancelResponse",
        "conversation": "ConversationPageResponse",
    }
    valid_files: dict[str, Any] = {
        name: {"schema": schema_names[name.split("-")[0]], "http": 200, "body": body}
        for name, body in valid.items()
    }
    for name, stream in status_streams.items():
        valid_files[name]["stream"] = stream
    for code, (http, message) in ERRORS.items():
        body: dict[str, Any] = {"isSuccess": False, "code": code, "message": message}
        if code == "RUN4091":
            body["result"] = {"activeRunId": ACTIVE_RUN_ID}
        valid_files[f"error-{code.lower()}"] = {
            "schema": "ErrorResponse",
            "http": http,
            "body": body,
        }

    invalid = {
        "status-retrying": (
            "RunStatusResponse",
            "내부 RETRYING은 RUNNING으로 투영해야 한다.",
            _ok(_status("RETRYING", 7)),
        ),
        "status-completed-without-disposition": (
            "RunStatusResponse",
            "COMPLETED는 responseDisposition 필수.",
            _ok(_status("COMPLETED", 6, completedAt=done)),
        ),
        "status-running-not-cancelable": (
            "RunStatusResponse",
            "RUNNING은 cancelable=true.",
            _ok(_status("RUNNING", 7, cancelable=False)),
        ),
        "status-failed-without-failure": (
            "RunStatusResponse",
            "FAILED는 failure 필수.",
            _ok(_status("FAILED", 8, completedAt=done)),
        ),
        "cancel-outcome-mismatch": (
            "RunCancelResponse",
            "already_terminal인데 CANCELING.",
            _ok(
                {"runId": RUN_ID, "status": "CANCELING", "outcome": "already_terminal"}
            ),
        ),
        "error-active-run-without-result": (
            "ErrorResponse",
            "RUN4091은 activeRunId 필수.",
            {"isSuccess": False, "code": "RUN4091", "message": ERRORS["RUN4091"][1]},
        ),
        "error-result-on-request-id-conflict": (
            "ErrorResponse",
            "RUN4092는 기존 run 정보를 노출하지 않는다.",
            {
                "isSuccess": False,
                "code": "RUN4092",
                "message": ERRORS["RUN4092"][1],
                "result": {"activeRunId": ACTIVE_RUN_ID},
            },
        ),
        "error-message-mismatch": (
            "ErrorResponse",
            "code별 고정 message와 다름.",
            {
                "isSuccess": False,
                "code": "RUN4041",
                "message": "노트 123은 다른 사용자의 노트입니다.",
            },
        ),
        "conversation-completed-without-response": (
            "ConversationPageResponse",
            "COMPLETED assistant는 assistantResponse 필수.",
            _ok(
                {
                    "noteId": NOTE_ID,
                    "activeRun": None,
                    "messages": [assistant("COMPLETED", RUN_ID, ASSISTANT_MESSAGE_ID)],
                    "nextBefore": None,
                }
            ),
        ),
        "conversation-streaming-with-response": (
            "ConversationPageResponse",
            "STREAMING placeholder는 본문이 없다(Candidate 비공개).",
            _ok(
                {
                    "noteId": NOTE_ID,
                    "activeRun": {"runId": RUN_ID, "status": "RUNNING", "lastSeq": 4},
                    "messages": [
                        assistant(
                            "STREAMING",
                            RUN_ID,
                            ASSISTANT_MESSAGE_ID,
                            assistantResponse=completed["assistantResponse"],
                        )
                    ],
                    "nextBefore": None,
                }
            ),
        ),
        "status-with-usage": (
            "RunStatusResponse",
            "상태 응답에 내부 usage — 응답도 additive-tolerant이지만 내부 키는 금지.",
            _ok(_status("RUNNING", 7, usage={"inputTokens": 10})),
        ),
        "status-with-attempt": (
            "RunStatusResponse",
            "상태 응답에 attempt.",
            _ok(_status("RUNNING", 7, attempt=2)),
        ),
        "cancel-with-fence-token": (
            "RunCancelResponse",
            "취소 응답에 fenceToken.",
            _ok(
                {
                    "runId": RUN_ID,
                    "status": "CANCELING",
                    "outcome": "accepted",
                    "fenceToken": 3,
                }
            ),
        ),
        "conversation-failure-class": (
            "ConversationPageResponse",
            "assistant failure에 failureClass.",
            _ok(
                {
                    "noteId": NOTE_ID,
                    "activeRun": None,
                    "messages": [
                        assistant(
                            "FAILED",
                            RUN_ID,
                            ASSISTANT_MESSAGE_ID,
                            failure={
                                "code": failed["code"],
                                "reason": None,
                                "message": failed["message"],
                                "recoverable": failed["recoverable"],
                                "failureClass": "provider_timeout",
                            },
                        )
                    ],
                    "nextBefore": None,
                }
            ),
        ),
        "conversation-active-run-deadline": (
            "ConversationPageResponse",
            "activeRun에 deadline.",
            _ok(
                {
                    "noteId": NOTE_ID,
                    "activeRun": {
                        "runId": RUN_ID,
                        "status": "RUNNING",
                        "lastSeq": 4,
                        "deadline": iso(START),
                    },
                    "messages": [],
                    "nextBefore": None,
                }
            ),
        ),
        "conversation-active-run-terminal-status": (
            "ConversationPageResponse",
            "activeRun은 PENDING|RUNNING|CANCELING만.",
            _ok(
                {
                    "noteId": NOTE_ID,
                    "activeRun": {"runId": RUN_ID, "status": "COMPLETED", "lastSeq": 4},
                    "messages": [],
                    "nextBefore": None,
                }
            ),
        ),
    }
    phase2_page = valid["conversation-verified-then-re-explanation"]

    def page_with(change) -> dict[str, Any]:
        body = deepcopy(phase2_page)
        change(body["result"]["messages"])
        return body

    invalid.update(
        {
            "conversation-re-explanation-verified": (
                "ConversationPageResponse",
                "[v1.1] 저장된 재설명 응답도 assuranceScope는 not_applicable뿐.",
                page_with(
                    lambda messages: messages[3]["assistant"].update(
                        assuranceScope="verified"
                    )
                ),
            ),
            "conversation-check-reason-exposed": (
                "ConversationPageResponse",
                "[v1.1] 저장·재조회 응답에도 검산기 사유 코드(checkReason)는 없다.",
                page_with(
                    lambda messages: messages[1]["assistant"]["solutionChecks"][0][
                        "checkableSteps"
                    ][0].update(checkReason="numeric")
                ),
            ),
        }
    )
    invalid_files = {
        name: {"schema": schema_name, "rationale": rationale, "body": body}
        for name, (schema_name, rationale, body) in invalid.items()
    }
    return valid_files, invalid_files


HEARTBEAT = ": heartbeat\n\n"


# --------------------------------------------------------------------------
# write
# --------------------------------------------------------------------------


def _dump(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def generated_files() -> dict[str, str]:
    """Every generated file (relative to ROOT) except manifest.json."""

    files: dict[str, str] = {"schemas/browser-run.schema.json": _dump(schema())}
    for name, value in valid_requests().items():
        files[f"requests/valid/{name}.json"] = _dump(value)
    for name, value in invalid_requests().items():
        files[f"requests/invalid/{name}.json"] = _dump(value)
    for name, value in fingerprint_cases().items():
        files[f"fingerprint/{name}.json"] = _dump(value)
    files["admission/decisions.json"] = _dump(admission_decisions())
    valid = streams()
    for name, value in valid.items():
        files[f"streams/valid/{name}.json"] = _dump(value)
    for name, value in invalid_streams(valid).items():
        files[f"streams/invalid/{name}.json"] = _dump(value)
    files["cursors/cases.json"] = _dump(cursor_cases(valid))
    valid_responses, invalid_responses = responses(valid)
    for name, value in valid_responses.items():
        files[f"responses/valid/{name}.json"] = _dump(value)
    for name, value in invalid_responses.items():
        files[f"responses/invalid/{name}.json"] = _dump(value)
    files["cancel/cases.json"] = _dump(cancel_cases())
    files["heartbeat.txt"] = HEARTBEAT
    files["heartbeat-invalid.json"] = _dump(heartbeat_invalid())
    files["CATALOG.md"] = catalog(files)
    return files


# --------------------------------------------------------------------------
# catalog (generated — the tables come from the same data as the fixtures)
# --------------------------------------------------------------------------

CATALOG_HEAD = """\
# browser-run-v1 fixture catalog

> 이 파일은 `build_fixtures.py`가 생성한다 — 직접 편집하지 않는다.

브라우저 run API(접수·재조회·SSE replay·상태·취소·대화 조회)의 wire 계약 artifact다
(task 1.75, Team-Proovy/Proovy-server#214). **계약 의미는 Proovy-server가 소유**하고
Brain은 versioned artifact만 보관한다 — `src/`는 이 디렉터리를 import하지 않으며
제품 API·제품 DB 소유권을 Brain으로 가져오지 않는다. 평가 정답·oracle과 무관하다.

## 버전

- `consumer_version`: `browser-run-fixtures-v1.1` (2026-11-12, task 2.07 Phase 2 additive 확장 — 아래 "Phase 2 확장")
  - 이전: `browser-run-fixtures-v1` (2026-10-17 최초 고정, 단계 2 합의 D1~D10). v1 fixture는 모두 byte 그대로 남아 있고 v1.1 schema도 통과한다.
- `contract_version`: `browser-run-v1`
- durable frame `contractVersion`: `brain-source-v1` (D1 — terminal-v2 browser schema의 `const`와 같은 값을 Server 소유 frame에도 사용)
- 참조 계약: terminal-fixtures-v2.1 · activity-fixtures-v1.1 · assistant-response-v1 · internal-run-fixtures-v2(canonical JSON) · page-selection-v1
- 요청 fingerprint: `browser-request-sha256-canonical-json-v1`

## 소비 규칙

- 소비자(Server Java DTO 검사, Front reducer/MSW)는 **merge된 proovy-brain commit + `consumer_version`**을 pin한다.
- 사본은 검증된 mirror로만 허용한다: `manifest.json`의 `files`(이 디렉터리 전체)와 `dependencies`
  (`terminal-v2/browser.schema.json`)의 sha256이 모두 일치해야 하고, 소비자는 pin에 manifest 자체의
  sha256도 기록한다. 사본을 손으로 고치지 않는다 — SSOT는 Brain이다.
- 변경: 의미가 바뀌면 새 `consumer_version`(새 디렉터리 또는 버전업)과 `change_history` 항목을 남긴다.
  이 CATALOG를 포함한 모든 파일은 `build_fixtures.py`가 생성한다 — builder를 고친 뒤
  `uv run python tests/fixtures/contracts/browser-run-v1/build_fixtures.py`로 재생성한다.
  `tests/contracts/test_browser_run_fixtures.py`가 builder 출력·digest 불일치에서 실패한다.
- schema 검증은 `manifest.schema_registry`처럼 `https://proovy.invalid/contracts/terminal-v2/browser.schema.json`을
  `terminal-v2/browser.schema.json`에 매핑한 registry로 한다(`$ref: ../../terminal-v2/browser.schema.json`).
- 요청 body는 **closed**(알 수 없는/금지 필드 = `RUN4001`). 응답·이벤트는 additive-tolerant(알 수 없는 추가 필드 무시,
  알려진 필드 검증)이지만, 내부 키(`attempt`·`fenceToken`·`sourceEventId`·`failureClass`·`requestFingerprint`·`usage`·
  `deadline`과 snake_case 형)는 frame·payload와 Server 소유 응답 객체 모두에서 schema가 거부한다.

## Phase 2 확장 (browser-run-fixtures-v1.1, task 2.07)

spec 04·ADR 0017·`docs/plan/27`의 화면 wire다. `contract_version`은 `browser-run-v1` 그대로이고 모두 **선택 필드**라
기존 frame·응답은 그대로 유효하다. 2.06 `SolutionVerification`을 Brain
`proovy_brain.contracts.browser_solution_check.completion_extension`으로 투영한 값이 fixture에 들어 있다.

- `run.completed` payload와 대화 조회 `AssistantMessageContent`에 세 필드가 **함께** 붙는다(`CompletionExtension`, 하나만 오면 거부).
  세 필드가 없으면 이 확장 이전 응답이다.
  - `responseKind`: `standard` | `re_explanation`. 재설명은 검증 없이 스트리밍하고 Front는 "추가 설명" 표시를 붙인다.
    재설명은 항상 `assuranceScope: not_applicable`, `solutionChecks: []`다.
  - `assuranceScope`(응답 배지): `not_applicable` solution은 집계에서 뺀다. 하나라도 `unverified`면 `unverified`,
    아니고 `verified`가 있으면 `verified`, 전부 `not_applicable`(또는 없음)이면 `not_applicable`. 검증됨+검증 대상 아님
    섞임은 `verified`다(사용자 결정 2026-11-12, `docs/plan/28`). 문제별 배지는 `solutionChecks[].assuranceScope`다.
  - `solutionChecks[]`(최대 20): `problemNumber`(없으면 null — 텍스트 입력), `assuranceScope`, `strategy`
    (`answer_cross_check`|`none`), `attempts`(1~3), `crossCheck`(일치 여부 플래그만, `none`이면 null),
    `disclosure`, `checkableSteps[]`. **순서 = Read-back `problems` 순서 = 응답 순서**다(번호가 null인 문제는 순서로 맞춘다).
  - `disclosure`(정답 공개 범위, Brain `DisclosureScope` — `assistantResponse`를 공개한 범위와 같다):
    `full`은 단계 공개, `hide_answer`는 **모든 단계** `withheld: true`(종류·✓✗ 표시만, `description`·`descriptionFormat`·
    `sympyStatement`는 null), `hide_solution`은 `checkableSteps: []`(배지·`crossCheck`만). 답을 정하는 단계만 골라
    가리는 판단은 아직 없어 hide_answer는 전부 가린다.
  - `checkableSteps[]`: `stepIndex`·`withheld`·`description`·`descriptionFormat`(`safe_markdown`|`plain_text`)·`sympyStatement`
    (null 또는 `name`·`value`·`cond`·`covers`·`lhs`·`rhs`·`choice`)·`stepKind`(`define`·`given`·`claim`·`case`·
    `choose`·`argue`)·`checkResult`(`verified` ✓·`falsified` ✗·`unparseable` 검산 안 됨·`not_checked` 검산 대상 아님).
    `descriptionFormat: plain_text`는 Brain 경계에서 SafeMarkdown을 통과하지 못한 그 단계 설명이다 — 수식·markdown 없이
    글자 그대로 그린다. `safe_markdown` 설명에는 `[[source:…]]` 마커가 없다(Brain이 지운다 — solutionChecks에는 출처 목록이
    없다). 단계 종류별 허용 결과와 공개 ✓ 단계의 수식 필수 규칙은 2.06 모델과 같고 schema가 검사한다.
- Brain → Server source(terminal-v2 `source.schema.json`의 `RunCompletedPayload`)에는 같은 값이 snake_case로 실린다:
  `response_kind`·`assurance_scope`·`solution_checks[]`(`problem_number`·`assurance_scope`·`strategy`·`attempts`·
  `cross_check{independent_answer_agrees, step_check_completed}`·`disclosure`·`checkable_steps[]{step_index, step_kind,
  check_result, withheld, description, description_format, sympy_statement}`). source는 null 값 키를 생략하고, browser
  변환(`to_browser_event_payload`)이 camelCase로 바꾸며 null 키를 채운다. terminal-v2 `pairs/phase2-*.json`이 그 예다.
- Activity Kind `read_back`(Read-back, "이렇게 읽었어요"): 진행 Activity의 첫 단계. label `reading_back_problems`,
  `activity.completed` payload `problems[]` = `problemNumber`(선택)·`questionText`·`choices`·`figureDescription`(선택).
  정의는 terminal-v2 browser schema(`ReadBackCompleted`, Brain `activity.py`에서 생성)에 있고 여기서는
  `ReadBackCompletedPayload`로 가리키며 `ReadBackGuard`가 답·단계·수식·내부 problem id를 거부한다.
  사용자 확인을 기다리지 않는다(틀리면 Run Cancellation).
- **비공개 원칙 유지**: `checkReason`, 독립 답 프로그램·값(`evidenceCode`·`independentAnswer`), `failureReason`,
  `problemId`는 어디에도 없다(schema가 거부). 단계·수식은 검증이 끝난 `run.completed`에서만 오고 Activity에는 없다.
- 기존 Phase 1 `verificationSummary`는 terminal-v2 그대로 남는다. `solutionChecks`가 있으면 화면 ✓·배지는 `solutionChecks`를 쓴다.
- 확장 fixture: `streams/valid`의 `[v1.1]` 7건(객관식 검증됨+Read-back · 불일치 미검증 · 여러 문제 섞임 · 증명 검증 대상 아님 ·
  재설명 · 정답 숨김 · 풀이 숨김), `streams/invalid`의 S7 19건, `responses/valid/conversation-verified-then-re-explanation`,
  `responses/invalid/conversation-*`(v1.1) 2건. `[v1.1]` stream의 `assistantResponse` 본문은 terminal-v2 pair를 재사용한 예시라
  `solutionChecks`의 문제와 내용이 같지 않다 — 구조 계약만 본다.

## 경로와 규칙 요약

| Method · Path | 성공 | 주요 오류 |
|---|---|---|
| `POST /api/conversations` (body `ConversationRunRequest`, `Accept: text/event-stream`) | 200 SSE seq 1 `run.created`부터. 같은 requestId·같은 사용자·같은 의미는 기존 run을 seq 1부터 replay | 401 · 400 `RUN4001`/`RUN4002` · 409 `RUN4092` · 404 `RUN4041` · 422 `RUN4221` · 409 `RUN4091{activeRunId}` · 429 `RUN4291`(+`Retry-After`) · 402 `RUN4021` |
| `GET /api/ai-runs/{runId}/events` (`Last-Event-ID` header 또는 `lastEventId` query) | 200 SSE: cursor 없으면 seq 1부터, 있으면 seq+1부터 replay 후 live | 404 `RUN4041` · 400 `RUN4003`(형식·범위·header/query 불일치) · 400 `RUN4004`(다른 run) |
| `GET /api/ai-runs/{runId}` | `RunStatusResponse` | 404 `RUN4041` |
| `POST /api/ai-runs/{runId}/cancel` (body 없음, 멱등) | `RunCancelResponse` (accepted·already_canceling·already_terminal) | 400 `RUN4001`(body 있음) · 404 `RUN4041` |
| `GET /api/conversations/{noteId}/messages?before&limit` | `ConversationPageResponse` | 404 `RUN4041` |

- 판정 순서: `admission/decisions.json`·`cancel/cases.json`·`cursors/cases.json`의 `precedence`. 같은 의미 재전송(replay)은
  Note·Active Run·rate limit·잔액보다 먼저다. 구조 검사는 대상 조회보다 먼저다.
- `RUN4092`·`RUN4041`은 다른 사용자/다른 의미, 없음/타인/삭제 중을 구분하지 않는 동일 body다(존재 oracle 방지).
- 스트림 시작 전 오류만 JSON `ApiResponse`(`isSuccess/code/message/result`), 시작 후에는 terminal 이벤트로 끝난다. code별 message는 고정이다.
- 공개 run status는 `PENDING|RUNNING|CANCELING|COMPLETED|FAILED|CANCELED` — 내부 `RETRYING`은 `RUNNING`으로 투영한다.
- 브라우저가 결정하지 못하는 값(scope·usage·attempt·fence·history·참조·corpus·conversationId·authToken·locale 등)은 요청 필드가 없다.
- 상태 응답 `lastSeq`는 그 시점 마지막 durable seq다. terminal 상태 fixture는 `stream`에 적은 `streams/valid` fixture와 같은 run이고
  `lastSeq`가 그 stream의 frame 수와 같다(그대로 cursor로 보내면 0 frame 후 종료).
- heartbeat는 15초마다 `: heartbeat` comment(id·data·event·seq·저장 없음, `heartbeat.txt`; 위반 예제 `heartbeat-invalid.json`).
- **runId = 시나리오.** 같은 run을 설명하는 fixture(stream·상태·취소·대화 조회·cursor `runs`)는 runId·requestId·
  userMessageId·assistantMessageId를 공유하고, 다른 시나리오는 다른 runId를 쓴다. 따라서 소비자가 valid fixture를 runId로
  교차 색인해도 충돌하지 않는다(`test_fixtures_describing_one_run_agree_by_run_id`). 진행 중 run은 시점 snapshot이라
  non-terminal 상태가 서로 다를 수 있다(예: `status-running` RUNNING → `cancel-accepted-running` CANCELING). invalid fixture는 색인 대상이 아니다.
- stream 규칙 S1~S7: `manifest.stream_rules`. replay와 live의 유일한 허용 차이는 read-time `sourceReferences[].available`.

## 형식·경계 판정

- **패턴은 입력 전체와 일치해야 한다.** schema 패턴은 `$` 대신 절대 끝 `(?![\\s\\S])`로 끝난다 — Python `re`·Java의 `$`는
  끝 줄바꿈 하나 앞에서도 매치되므로 `"<uuid>\\n"`을 통과시킨다. 소비자 구현은 full match(Java `matches()`, Python `fullmatch`)로 한다.
- ID: `requestId`·`runId`·`messageId`·`attachmentId`는 소문자 canonical UUID. `noteId`는 선행 0 없는 10진 문자열 1..9223372036854775807
  (Java `Long.MAX_VALUE`, 초과는 404가 아니라 `RUN4001`). `noteAssetId`는 opaque `[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}`.
- event id/cursor: `{runId}:{seq}` — 소문자 UUID, seq 1..9007199254740991(2^53-1), 선행 0 없음. 상한은 schema 패턴 자체에 들어 있다.
  `cursors/cases.json`의 `schemaRejects`가 true인 값은 `EventId` schema가 거부하고, false인 400은 범위(마지막 seq 초과)·header/query 불일치다.
  `thenLive`는 `runs.<run>.status`가 terminal이 아닐 때만 true다(`thenLiveRule`).
- **길이(D6)**: `text`·`latex` 각각 **strip 후 1~10,000 code point**(합의 초안 §3.2 문구). 원문 기준이 아니다 — 양끝 공백이 붙어 원문이
  10,000을 넘어도 strip 후 10,000이면 접수한다(`text-at-limit-surrounded-by-blanks`). schema는 비어 있지 않음·공백만이 아님만 검사하고
  상한은 계약 검사(`RUN4001`, `schema_rejects: false`)다. 단위는 code point다 — Java `String.length()`·JS `.length`(UTF-16 단위)로
  세면 astral 문자 fixture(`text-at-limit-astral`·`text-too-long-astral`)에서 틀린다.
- **strip 집합**: internal-run-v1과 같은 Python `str.strip()` 집합 — `\\t \\n \\v \\f \\r U+001C~U+001F space U+0085 U+00A0 U+1680
  U+2000~U+200A U+2028 U+2029 U+202F U+205F U+3000`. Java `String.strip()`/`trim()`은 U+00A0·U+202F 등을 남기므로 이 집합을 직접 구현한다
  (`fingerprint/same-non-java-whitespace`).
- **request fingerprint**: `requestId` 제외, `text`·`latex` strip, 비순서 배열 정렬, `[]`·`null`은 부재와 같음, `noteId` 부재/존재는 다름
  → internal-run-v1 `canonical_json` → sha256. **fingerprint 전에 기본값을 채우지 않는다** — `requestedOutputs: []`(Brain 추론)를
  `["text"]`로 바꿔 정규화하면 안 된다(`fingerprint/different-outputs-explicit-vs-inferred`).
- `turnAttachmentIds` wire 상한 10(실제 개수·용량은 Server policy → `RUN4221`).

## 기존 계약과의 일관성

- `streams/`의 activity frame은 activity-v1 source fixture를 Brain `to_browser_event_payload`로 변환한 값이고,
  `run.completed`/`run.failed` payload는 terminal-v2 `pairs/*.json`의 browser payload와 `assistantMessageId`만 다르다(테스트가 byte 비교).
  terminal-v2 pair는 publication-v1 valid에서 조립되므로 clarification/unsupported_only/substantive의 공개 구조도 같다.
- 대화 조회의 `assistantResponse`는 terminal-v2 `AssistantResponseV1`을 그대로 `$ref`한다. 대화 조회 예제는 메시지가 `createdAt`
  오름차순이고 assistant마다 run이 다르며, 각 assistant 앞에 같은 run의 user 메시지가 있다.
- 기존 fixture의 의미·파일은 바꾸지 않았다(v1.1 확장 포함 — v1 fixture는 byte 그대로). `[v1.1]` stream의 `run.completed`는
  terminal-v2 pair payload에 `responseKind`·`assuranceScope`·`solutionChecks`만 더한 값이다. 참고: terminal-v2 browser schema의 opaque ID 패턴은 `$`로 끝난다(이 artifact 밖,
  terminal-v2 변경 대상).

## 합의 초안 §11.1 예제와의 대응

- `replay-from-cursor` → `cursors/cases.json`(`valid-middle`·`valid-active` 등 — stream 사본을 두지 않는다).
- `retrying-projected-running` → `responses/invalid/status-retrying`(RETRYING 노출 거부)과 `status-running`.
- `events/invalid` → `streams/invalid`. `heartbeat-with-id` → `heartbeat-invalid.json`(frame이 아니라 SSE comment라 따로 둔다).
- cancel body 있음 → `cancel/cases.json`(`empty-object-body`·`reason-body`, 400 `RUN4001`).

## 파일
"""


def _cell(text: Any) -> str:
    return str(text).replace("|", "\\|").replace("\n", "\\n")


def _table(header: list[str], rows: list[list[Any]]) -> str:
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    lines += ["| " + " | ".join(_cell(cell) for cell in row) + " |" for row in rows]
    return "\n".join(lines) + "\n"


def _code(value: Any) -> str:
    return f"`{value}`"


def catalog(files: dict[str, str]) -> str:
    def group(prefix: str) -> list[tuple[str, Any]]:
        return [
            (path[len(prefix) : -len(".json")], json.loads(text))
            for path, text in sorted(files.items())
            if path.startswith(prefix) and path.endswith(".json")
        ]

    def expected(value: dict[str, Any]) -> str:
        if value.get("code"):
            return f"{value['http']} {value['code']}"
        return f"{value['http']} {value.get('outcome', 'OK')}"

    def rejects(value: bool, why: str) -> str:
        return "예" if value else f"아니오({why})"

    def cursor(raw: str | None, run_id: str) -> str:
        return "–" if raw is None else _code(raw.replace(run_id, "{runId}"))

    sections = [CATALOG_HEAD]
    sections.append("### requests/valid\n\n")
    sections.append(
        _table(
            ["파일", "판정 근거"],
            [[_code(n), c["rationale"]] for n, c in group("requests/valid/")],
        )
    )
    sections.append("\n### requests/invalid\n\n")
    sections.append(
        _table(
            ["파일", "기대", "schema 거부", "근거"],
            [
                [
                    _code(n),
                    f"{c['expected']['http']} `{c['expected']['code']}`",
                    rejects(c["schema_rejects"], "계약 검사"),
                    c["rationale"],
                ]
                for n, c in group("requests/invalid/")
            ],
        )
    )
    sections.append("\n### fingerprint\n\n")
    sections.append(
        _table(
            ["파일", "관계", "근거"],
            [
                [_code(n), c["relation"], c["rationale"]]
                for n, c in group("fingerprint/")
            ],
        )
    )
    decisions = json.loads(files["admission/decisions.json"])
    sections.append("\n### admission/decisions.json cases\n\n")
    sections.append(
        _table(
            ["case", "기대", "근거"],
            [
                [_code(c["name"]), expected(c["expected"]), c["rationale"]]
                for c in decisions["cases"]
            ],
        )
    )
    sections.append("\n### streams/valid\n\n")
    sections.append(
        _table(
            ["파일", "최종 상태", "frame 수", "내용"],
            [
                [_code(n), c["finalStatus"], len(c["frames"]), c["rationale"]]
                for n, c in group("streams/valid/")
            ],
        )
    )
    sections.append("\n### streams/invalid\n\n")
    sections.append(
        _table(
            ["파일", "위반 규칙", "schema 거부", "근거"],
            [
                [
                    _code(n),
                    c["violates"],
                    rejects(c["schema_rejects"], "순서 규칙"),
                    c["rationale"],
                ]
                for n, c in group("streams/invalid/")
            ],
        )
    )
    cursors = json.loads(files["cursors/cases.json"])
    run_ids = {name: run["runId"] for name, run in cursors["runs"].items()}
    sections.append("\n### cursors/cases.json\n\n")
    runs = ", ".join(
        f"`{name}` = `{run['runId']}` {run['status']}, "
        f"lastDurableSeq {run['lastDurableSeq']}"
        for name, run in cursors["runs"].items()
    )
    sections.append(f"runs: {runs}\n\n")
    sections.append(
        _table(
            ["case", "run", "header", "query", "schema 거부", "기대"],
            [
                [
                    _code(c["name"]),
                    c["run"],
                    cursor(c["lastEventIdHeader"], run_ids[c["run"]]),
                    cursor(c["lastEventIdQuery"], run_ids[c["run"]]),
                    "예" if c["schemaRejects"] else "아니오",
                    expected(c["expected"])
                    if "code" in c["expected"]
                    else f"200 replay seq {c['expected']['replayFromSeq']}~ "
                    + ("+ live" if c["expected"]["thenLive"] else "후 종료")
                    + (" (0 frame)" if c["expected"].get("frameCount") == 0 else ""),
                ]
                for c in cursors["cases"]
            ]
            + [
                [
                    _code(c["name"]),
                    c["access"],
                    cursor(c["lastEventIdHeader"], RUN_ID),
                    "–",
                    "아니오",
                    expected(c["expected"]),
                ]
                for c in cursors["accessCases"]
            ],
        )
    )
    cancel = json.loads(files["cancel/cases.json"])
    sections.append("\n### cancel/cases.json\n\n")
    sections.append(
        _table(
            ["case", "body", "run", "기대", "근거"],
            [
                [
                    _code(c["name"]),
                    "–" if c["rawBody"] is None else _code(c["rawBody"]),
                    c["run"],
                    expected(c["expected"]),
                    c["rationale"],
                ]
                for c in cancel["cases"]
            ],
        )
    )
    sections.append("\n### responses/valid\n\n")
    sections.append(
        _table(
            ["파일", "schema", "HTTP", "같은 run의 stream"],
            [
                [
                    _code(n),
                    _code(c["schema"]),
                    c["http"],
                    _code(c["stream"]) if "stream" in c else "–",
                ]
                for n, c in group("responses/valid/")
            ],
        )
    )
    sections.append("\n### responses/invalid\n\n")
    sections.append(
        _table(
            ["파일", "schema", "근거"],
            [
                [_code(n), _code(c["schema"]), c["rationale"]]
                for n, c in group("responses/invalid/")
            ],
        )
    )
    heartbeat = json.loads(files["heartbeat-invalid.json"])
    sections.append("\n### heartbeat-invalid.json\n\n")
    sections.append(
        _table(
            ["case", "근거"],
            [[_code(c["name"]), c["rationale"]] for c in heartbeat["cases"]],
        )
    )
    sections.append("\n### 기타\n\n")
    sections.append(
        _table(
            ["파일", "내용"],
            [
                [
                    "`schemas/browser-run.schema.json`",
                    "모든 요청·응답·Server 소유 frame·`BrowserFrame` 정의",
                ],
                ["`heartbeat.txt`", "live heartbeat comment 원문"],
                ["`manifest.json`", "버전·registry·limits·stream_rules·파일 digest"],
                ["`build_fixtures.py`", "이 CATALOG를 포함한 모든 fixture 생성기"],
            ],
        )
    )
    return "".join(sections)


def _sha256(text: str | bytes) -> str:
    data = text.encode() if isinstance(text, str) else text
    return hashlib.sha256(data).hexdigest()


def manifest(files: dict[str, str]) -> dict[str, Any]:
    builder = (ROOT / "build_fixtures.py").read_bytes()
    digests = {f"browser-run-v1/{path}": _sha256(text) for path, text in files.items()}
    digests["browser-run-v1/build_fixtures.py"] = _sha256(builder)
    terminal_schema = (TERMINAL / "browser.schema.json").read_bytes()
    return {
        "consumer_version": CONSUMER_VERSION,
        "contract_version": CONTRACT_VERSION,
        "original_path": "tests/fixtures/contracts/browser-run-v1",
        "meaning_owner": "Proovy-server (task 1.75, Team-Proovy/Proovy-server#214)",
        "frame_contract_version": FRAME_CONTRACT_VERSION,
        "fingerprint_algorithm": FINGERPRINT_ALGORITHM,
        "references": {
            "terminal": "terminal-fixtures-v2.1",
            "activity": "activity-fixtures-v1.1",
            "response": "assistant-response-v1",
            "internal_run": "internal-run-fixtures-v2",
            "page_selection": "page-selection-v1",
        },
        "schema_registry": {
            SCHEMA_ID: "browser-run-v1/schemas/browser-run.schema.json",
            TERMINAL_SCHEMA_ID: "terminal-v2/browser.schema.json",
        },
        "limits": {
            "text_max_code_points": TEXT_MAX,
            "latex_max_code_points": LATEX_MAX,
            "turn_attachment_ids_wire_max": ATTACHMENT_WIRE_MAX,
            "heartbeat_seconds": HEARTBEAT_SECONDS,
            "max_seq": MAX_SEQ,
        },
        "stream_rules": STREAM_RULES,
        "consumption_policy": (
            "Consumers pin a merged proovy-brain commit + consumer_version. A copy "
            "is allowed only as a verified mirror: every file listed in `files` "
            "must match its sha256, and the pinned manifest sha256 must match. "
            "Brain stays the single source of truth; changes require a new "
            "consumer_version with a change_history entry."
        ),
        "change_history": [
            {
                "date": "2026-10-17",
                "consumer_version": INITIAL_CONSUMER_VERSION,
                "reason": "Task 1.75 initial freeze of the browser run API wire contract "
                "(admission/replay/status/cancel/conversation, cursor, Server-owned "
                "frames) agreed at the stage-2 gate (D1-D10).",
            },
            {
                "date": "2026-11-12",
                "consumer_version": CONSUMER_VERSION,
                "reason": "Task 2.07 additive (contract_version stays browser-run-v1): "
                "run.completed payload and conversation AssistantMessageContent gain "
                "optional responseKind (standard|re_explanation), assuranceScope "
                "(verified|unverified|not_applicable) and solutionChecks[] with "
                "per-solution disclosure (full|hide_answer|hide_solution) and "
                "checkableSteps[] (stepIndex/withheld/description/descriptionFormat/"
                "sympyStatement/stepKind/checkResult); Activity Kind read_back "
                "(terminal-v2 browser schema, guarded here). Every v1 fixture is "
                "byte-identical and still valid; absent fields mean a response from "
                "before the extension.",
            },
        ],
        "files": dict(sorted(digests.items())),
        "dependencies": {"terminal-v2/browser.schema.json": _sha256(terminal_schema)},
    }


def write_all() -> None:
    files = generated_files()
    for directory in (
        "schemas",
        "requests",
        "fingerprint",
        "admission",
        "streams",
        "cursors",
        "cancel",
        "responses",
    ):
        target = ROOT / directory
        if target.exists():
            for stale in sorted(target.rglob("*.json"), reverse=True):
                stale.unlink()
    for path, text in files.items():
        target = ROOT / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
    (ROOT / "manifest.json").write_text(_dump(manifest(files)))


if __name__ == "__main__":
    write_all()
