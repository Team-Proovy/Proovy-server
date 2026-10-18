from __future__ import annotations

import json
from copy import deepcopy
from datetime import UTC, datetime
from pathlib import Path

from proovy_brain.contracts.publication import parse_publication_request
from proovy_brain.contracts.source_progress import (
    SourceEventType,
    derive_source_event_id,
)
from proovy_brain.contracts.terminal import (
    FAILURE_MESSAGES,
    INVALID_ATTACHMENT_MESSAGES,
    AttachmentFailureReason,
    FailureCode,
    browser_event_schema,
    parse_source_event,
    source_event_schema,
    to_browser_event_payload,
    to_source_event_payload,
)
from proovy_brain.features.response_publication.service import assemble_response
from proovy_brain.features.terminal_delivery.service import derive_terminal_event_id

ROOT = Path(__file__).resolve().parent
PUBLICATION = ROOT.parent / "publication-v1/valid"
CONTEXT = {
    "contract_version": "brain-source-v1",
    "run_id": "run-7",
    "attempt": 1,
    "fence_token": "fence-9f8c",
    "source_event_id": derive_terminal_event_id("run-7"),
    "occurred_at": datetime(2026, 10, 1, 9, tzinfo=UTC)
    .isoformat()
    .replace("+00:00", "Z"),
}
NAMES = (
    "direct-verified",
    "supporting-logical-move",
    "zero-step-fallback",
    "inconclusive-unverified",
    "check-with-correction",
    "hide-answer",
    "clarification",
    "unsupported-only",
    "mixed-substantive-notice",
    "multi-page",
)


def save(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def completed_pair(name: str, request_raw: dict | None = None) -> dict:
    if request_raw is None:
        request_raw = json.loads((PUBLICATION / f"{name}.json").read_text())
    request = parse_publication_request(request_raw)
    published = assemble_response(request)
    artifacts = []
    for page in request.pages:
        for block in page.blocks:
            if block.kind != "solution" or block.solution_id is None:
                continue
            summary = next(
                item
                for item in published.verification_summary
                if item.solution_id == block.solution_id
            )
            artifacts.append(
                {
                    "schema_version": "verified-solution-v1",
                    "solution_id": block.solution_id,
                    "version": 1,
                    "problem_statement": "주어진 명시 조건을 만족하는 결론을 구한다.",
                    "problem_conditions": [
                        {
                            "condition_id": "condition-1",
                            "text": "문제의 명시 조건을 사용한다.",
                        }
                    ],
                    "solution_text": block.draft.text,
                    "claims": [
                        {
                            "claim_id": claim.claim_id,
                            "statement": "최종 답은 $x=2$이다."
                            if name == "hide-answer"
                            else "명시 조건에서 제시한 결론이 성립한다.",
                        }
                        for claim in summary.claims
                    ],
                    "step_marks": [
                        {
                            "step_id": step.step_id,
                            "status": step.status.value,
                            **(
                                {
                                    "reason": next(
                                        item.reason
                                        for claim in summary.claims
                                        for item in claim.unconfirmed_steps
                                        if item.step_id == step.step_id
                                    )
                                }
                                if step.status.value == "unconfirmed"
                                else {}
                            ),
                        }
                        for step in block.report.steps
                    ],
                    "verification": summary.model_dump(mode="json", exclude_none=True),
                    "verification_version": "chain-verification-report-v3",
                    "source_ref_ids": [
                        ref.source_ref_id for ref in request.source_references
                    ],
                }
            )
    provenance = [
        {
            "asset_id": ref.asset_id,
            "content_version": "content-v1",
            "sha256": "a" * 64,
            "page": page,
            "understanding_version": "page-understanding-v1",
        }
        for ref in request.source_references
        for page in range(ref.page_start, ref.page_end + 1)
    ]
    event = parse_source_event(
        {
            **CONTEXT,
            "type": "run.completed",
            "payload": {
                "response_disposition": published.disposition.value,
                "clarification_reason": published.clarification_reason,
                "assistant_response": published.response.model_dump(
                    mode="json", exclude_none=True
                ),
                "verified_solutions": artifacts,
                "existing_solution_references": [
                    reference.model_copy(update={"verification": summary}).model_dump(
                        mode="json", exclude_none=True
                    )
                    for summary in published.verification_summary
                    for reference in request.existing_solution_references
                    if reference.solution_id == summary.solution_id
                ]
                or None,
                "selected_page_provenance": provenance,
                "model": "fake/main-model",
                "tier": "main",
                "verification_summary": [
                    item.model_dump(mode="json", exclude_none=True)
                    for item in published.verification_summary
                ],
                "source_labels": [
                    item.model_dump(mode="json") for item in published.source_labels
                ],
            },
        }
    )
    server = {
        "assistant_message_id": "message-7",
        "seq": 9,
        "display_labels": published.display_labels,
        "availability": {ref.source_ref_id: True for ref in request.source_references},
    }
    return {
        "publication_request": request_raw,
        "source": to_source_event_payload(event),
        "server": server,
        "browser": to_browser_event_payload(event, **server),
    }


def review_golden_pairs() -> dict[str, dict]:
    summary = completed_pair("direct-verified")["source"]["payload"][
        "verification_summary"
    ][0]
    text = "양변에 같은 연산을 적용하는 이유를 설명합니다."
    request = {
        "disposition": "substantive",
        "disclosure": {"scope": "full", "judgment_completed": True},
        "pages": [
            {
                "presentation": "main",
                "blocks": [
                    {
                        "kind": "explanation",
                        "solution_id": "solution-1",
                        "claim_ids": ["claim-1"],
                        "draft": {"text": text},
                    }
                ],
            }
        ],
        "existing_solution_references": [
            {
                "solution_id": "solution-1",
                "version": 1,
                "claim_match_checked": True,
                "verification": summary,
            }
        ],
    }
    result = {"explain-existing": completed_pair("explain-existing", request)}
    hidden = deepcopy(request)
    hidden["disclosure"]["scope"] = "hide_answer"
    full_text = text + " 최종 답은 $x=2$입니다."
    hidden["pages"][0]["blocks"][0]["draft"] = {
        "text": full_text,
        "withheld": [{"start": len(text), "end": len(full_text)}],
    }
    result["explain-existing-hide-answer"] = completed_pair(
        "explain-existing-hide-answer", hidden
    )
    conflict_pages = [
        {"asset_id": asset, "page": 1} for asset in ("asset-a", "asset-b")
    ]
    progress = [
        to_source_event_payload(
            parse_source_event(
                {
                    **CONTEXT,
                    "type": event_type.value,
                    "source_event_id": derive_source_event_id(
                        "run-7", "activity-1", revision, event_type
                    ),
                    "payload": {
                        "activity_id": "activity-1",
                        "kind": "note_retrieval",
                        **payload,
                    },
                }
            )
        )
        for event_type, revision, payload in (
            (SourceEventType.ACTIVITY_STARTED, 0, {"label": "searching_notes"}),
            (
                SourceEventType.ACTIVITY_COMPLETED,
                1,
                {"match_status": "conflicting_content", "pages": conflict_pages},
            ),
        )
    ]
    clarification = {
        "disposition": "clarification_only",
        "disclosure": {"scope": "full", "judgment_completed": True},
        "clarification": {
            "reason": "ambiguous_target",
            "draft": {"text": "어떤 자료의 문제 조건을 기준으로 할까요?"},
        },
    }
    substantive = {
        "disposition": "substantive",
        "disclosure": {"scope": "full", "judgment_completed": True},
        "pages": [
            {
                "presentation": "main",
                "blocks": [
                    {
                        "kind": "rich_text",
                        "draft": {
                            "text": "첫 자료와 두 번째 자료의 조건이 다릅니다.[[source:sref-1]][[source:sref-2]]"
                        },
                    }
                ],
            }
        ],
        "source_references": [
            {
                "source_ref_id": f"sref-{index}",
                "asset_id": asset,
                "page_start": 1,
                "page_end": 1,
            }
            for index, asset in enumerate(("asset-a", "asset-b"), 1)
        ],
        "source_labels": [
            {"source_ref_id": f"sref-{index}", "label": f"자료 {index}"}
            for index in (1, 2)
        ],
    }
    for name, request in (
        ("conflict-clarification", clarification),
        ("conflict-substantive", substantive),
    ):
        result[name] = {**completed_pair(name, request), "progress_sources": progress}
    return result


def build() -> None:
    for name in NAMES:
        save(ROOT / "pairs" / f"{name}.json", completed_pair(name))
    failure = parse_source_event(
        {
            **CONTEXT,
            "type": "run.failed",
            "payload": {
                "code": "verification_failed",
                "message": FAILURE_MESSAGES[FailureCode.VERIFICATION_FAILED],
                "failure_class": "verification",
                "recoverable": True,
            },
        }
    )
    server = {"assistant_message_id": "message-7", "seq": 9}
    save(
        ROOT / "pairs/failure.json",
        {
            "source": to_source_event_payload(failure),
            "server": server,
            "browser": to_browser_event_payload(failure, **server),
        },
    )
    invalid_attachment = parse_source_event(
        {
            **CONTEXT,
            "type": "run.failed",
            "payload": {
                "code": "invalid_attachment",
                "reason": "pdf_encrypted",
                "message": INVALID_ATTACHMENT_MESSAGES[
                    AttachmentFailureReason.PDF_ENCRYPTED
                ],
                "failure_class": "attachment",
                "recoverable": False,
            },
        }
    )
    save(
        ROOT / "pairs/failure-invalid-attachment.json",
        {
            "source": to_source_event_payload(invalid_attachment),
            "server": server,
            "browser": to_browser_event_payload(invalid_attachment, **server),
        },
    )
    for name, mutate in (
        ("reason-on-non-attachment-code", "stray_reason"),
        ("attachment-missing-reason", "drop_reason"),
        ("attachment-message-mismatch", "wrong_message"),
        ("attachment-unknown-reason", "unknown_reason"),
        ("attachment-recoverable", "retryable"),
    ):
        if name == "reason-on-non-attachment-code":
            source = to_source_event_payload(failure)
            browser = to_browser_event_payload(failure, **server)
            source["payload"]["reason"] = "pdf_encrypted"
            browser["payload"]["reason"] = "pdf_encrypted"
        else:
            source = to_source_event_payload(invalid_attachment)
            browser = to_browser_event_payload(invalid_attachment, **server)
            if mutate == "drop_reason":
                del source["payload"]["reason"]
                del browser["payload"]["reason"]
            elif mutate == "retryable":
                source["payload"]["recoverable"] = True
                browser["payload"]["recoverable"] = True
            elif mutate == "wrong_message":
                source["payload"]["message"] = FAILURE_MESSAGES[
                    FailureCode.BUDGET_EXCEEDED
                ]
                browser["payload"]["message"] = FAILURE_MESSAGES[
                    FailureCode.BUDGET_EXCEEDED
                ]
            else:
                source["payload"]["reason"] = "DO-NOT-LEAK-raw-payload"
                browser["payload"]["reason"] = "DO-NOT-LEAK-raw-payload"
        save(
            ROOT / "invalid" / f"{name}.json",
            {
                "source": source,
                "browser": browser,
                "source_outcome": "reject",
                "browser_outcome": "reject",
            },
        )
    # failure_class is a source-only field: a class that disagrees with
    # the code mutates only the source side; the browser projection of
    # the unmutated event stays valid.
    source = to_source_event_payload(invalid_attachment)
    source["payload"]["failure_class"] = "infrastructure"
    browser = to_browser_event_payload(invalid_attachment, **server)
    save(
        ROOT / "invalid" / "attachment-wrong-class.json",
        {
            "source": source,
            "browser": browser,
            "source_outcome": "reject",
            "browser_outcome": "valid_public_projection",
            "browser_expected": browser,
        },
    )
    direct = completed_pair("direct-verified")
    for name in (
        "phantom-solution",
        "phantom-claim",
        "overclaimed-coverage",
        "kind-type-mismatch",
        "failure-with-answer",
        "raw-extra",
        "raw-discriminator",
    ):
        pair = {
            "source": deepcopy(direct["source"]),
            "browser": deepcopy(direct["browser"]),
            "source_outcome": "reject",
            "browser_outcome": "reject",
        }
        source = pair["source"]["payload"]
        browser = pair["browser"]["payload"]
        if name in ("phantom-solution", "phantom-claim", "overclaimed-coverage"):
            block = source["assistant_response"]["response_pages"][0]["result_blocks"][
                0
            ]
            browser_block = browser["assistantResponse"]["responsePages"][0][
                "resultBlocks"
            ][0]
            if name == "phantom-solution":
                block["solution_id"] = "solution-99"
                browser_block["solutionId"] = "solution-99"
            elif name == "phantom-claim":
                block["claim_ids"] = ["claim-99"]
                browser_block["claimIds"] = ["claim-99"]
            else:
                block["verification"] = "partially_verified"
                browser_block["verification"] = "partially_verified"
        elif name == "kind-type-mismatch":
            pair["source"]["type"] = "activity.updated"
            pair["browser"]["type"] = "activity.updated"
        elif name == "failure-with-answer":
            pair["source"]["type"] = "run.failed"
            pair["browser"]["type"] = "run.failed"
        elif name == "raw-extra":
            source["DO-NOT-LEAK-raw-payload"] = "DO-NOT-LEAK-raw-payload"
            browser["DO-NOT-LEAK-raw-payload"] = "DO-NOT-LEAK-raw-payload"
            pair["browser_outcome"] = "ignore_additive"
            pair["browser_expected"] = direct["browser"]
        else:
            pair["source"]["type"] = "DO-NOT-LEAK-raw-payload"
            pair["browser"]["type"] = "DO-NOT-LEAK-raw-payload"
        save(ROOT / "invalid" / f"{name}.json", pair)
    for name in (
        "phantom-source",
        "duplicate-page",
        "unverified-as-verified",
        "guidance-with-sources",
        "missing-selected-page",
        "source-version-conflict",
        "code",
        "stdout",
        "metadata",
        "video_target",
        "unsafe-failure-message",
    ):
        pair = {
            "source": deepcopy(direct["source"]),
            "browser": deepcopy(direct["browser"]),
            "source_outcome": "reject",
            "browser_outcome": "reject",
        }
        source = pair["source"]["payload"]
        browser = pair["browser"]["payload"]
        if name == "phantom-source":
            source["assistant_response"]["response_pages"][0]["result_blocks"][0][
                "body"
            ] += "[[source:sref-99]]"
            browser["assistantResponse"]["responsePages"][0]["resultBlocks"][0][
                "body"
            ] += "[[source:sref-99]]"
        elif name == "duplicate-page":
            source["assistant_response"]["response_pages"].append(
                deepcopy(source["assistant_response"]["response_pages"][0])
            )
            browser["assistantResponse"]["responsePages"].append(
                deepcopy(browser["assistantResponse"]["responsePages"][0])
            )
        elif name == "unverified-as-verified":
            unverified = completed_pair("inconclusive-unverified")
            pair["source"] = unverified["source"]
            pair["browser"] = unverified["browser"]
            pair["source"]["payload"]["assistant_response"]["response_pages"][0][
                "result_blocks"
            ][0]["verification"] = "verified"
            pair["browser"]["payload"]["assistantResponse"]["responsePages"][0][
                "resultBlocks"
            ][0]["verification"] = "verified"
        elif name == "guidance-with-sources":
            guidance = completed_pair("clarification")
            pair["source"] = guidance["source"]
            pair["browser"] = guidance["browser"]
            pair["source"]["payload"]["assistant_response"]["source_references"] = (
                source["assistant_response"]["source_references"]
            )
            pair["browser"]["payload"]["assistantResponse"]["sourceReferences"] = (
                browser["assistantResponse"]["sourceReferences"]
            )
        elif name == "unsafe-failure-message":
            pair["source"] = to_source_event_payload(failure)
            pair["browser"] = to_browser_event_payload(failure, **server)
            pair["source"]["payload"]["message"] = "DO-NOT-LEAK-raw-payload"
            pair["browser"]["payload"]["message"] = "DO-NOT-LEAK-raw-payload"
        else:
            if name == "missing-selected-page":
                source["selected_page_provenance"] = source["selected_page_provenance"][
                    :1
                ]
                pair["browser_outcome"] = "valid_public_projection"
            elif name == "source-version-conflict":
                source["selected_page_provenance"][1]["content_version"] = (
                    "content-other"
                )
                pair["browser_outcome"] = "valid_public_projection"
            else:
                source[name] = "DO-NOT-LEAK-raw-payload"
                browser[name] = "DO-NOT-LEAK-raw-payload"
                pair["browser_outcome"] = "ignore_additive"
            pair["browser_expected"] = direct["browser"]
        save(ROOT / "invalid" / f"{name}.json", pair)
    save(
        ROOT / "redelivery.json",
        {
            "same_semantic": {
                "first": direct["source"],
                "retry": direct["source"],
                "outcome": "same_reserved_event",
            },
            "changed_outcome": {
                "first": direct["source"],
                "retry": to_source_event_payload(failure),
                "outcome": "reject",
            },
        },
    )
    review_pairs = review_golden_pairs()
    for name, pair in review_pairs.items():
        save(ROOT / "pairs" / f"{name}.json", pair)
    existing = review_pairs["explain-existing"]
    for name in (
        "existing-phantom",
        "existing-mismatched-claim",
        "existing-coverage-mismatch",
        "existing-unchecked",
        "existing-full-answer-extra",
    ):
        pair = {
            "source": deepcopy(existing["source"]),
            "browser": deepcopy(existing["browser"]),
            "source_outcome": "reject",
            "browser_outcome": "reject",
        }
        source = pair["source"]["payload"]
        browser = pair["browser"]["payload"]
        if name == "existing-phantom":
            source["assistant_response"]["response_pages"][0]["result_blocks"][0][
                "solution_id"
            ] = "solution-99"
            browser["assistantResponse"]["responsePages"][0]["resultBlocks"][0][
                "solutionId"
            ] = "solution-99"
        elif name == "existing-mismatched-claim":
            source["assistant_response"]["response_pages"][0]["result_blocks"][0][
                "claim_ids"
            ] = ["claim-99"]
            browser["assistantResponse"]["responsePages"][0]["resultBlocks"][0][
                "claimIds"
            ] = ["claim-99"]
        elif name == "existing-coverage-mismatch":
            source["verification_summary"][0]["claims"][0]["coverage"] = "supporting"
            browser["verificationSummary"][0]["claims"][0]["coverage"] = "supporting"
        else:
            if name == "existing-unchecked":
                source["existing_solution_references"][0]["claim_match_checked"] = False
                pair["browser_outcome"] = "valid_public_projection"
            else:
                source["existing_solution_references"][0]["solution_text"] = (
                    "DO-NOT-LEAK-raw-payload"
                )
                browser["existingSolutionReferences"] = [
                    {"solutionText": "DO-NOT-LEAK-raw-payload"}
                ]
                pair["browser_outcome"] = "ignore_additive"
            pair["browser_expected"] = existing["browser"]
        save(ROOT / "invalid" / f"{name}.json", pair)
    for name in ("missing-activity-kind", "missing-block-kind"):
        if name == "missing-activity-kind":
            source = json.loads(
                (
                    ROOT.parent / "activity-v1/valid/input-analysis-started.json"
                ).read_text()
            )
            browser = to_browser_event_payload(parse_source_event(source), seq=1)
            del source["payload"]["kind"]
            del browser["payload"]["kind"]
        else:
            source, browser = deepcopy(direct["source"]), deepcopy(direct["browser"])
            del source["payload"]["assistant_response"]["response_pages"][0][
                "result_blocks"
            ][0]["kind"]
            del browser["payload"]["assistantResponse"]["responsePages"][0][
                "resultBlocks"
            ][0]["kind"]
        save(
            ROOT / "invalid" / f"{name}.json",
            {
                "source": source,
                "browser": browser,
                "source_outcome": "reject",
                "browser_outcome": "reject",
                "schema_outcome": "reject",
            },
        )
    save(ROOT / "source.schema.json", source_event_schema())
    save(ROOT / "browser.schema.json", browser_event_schema())
    save(
        ROOT / "manifest.json",
        {
            "consumer_version": "terminal-fixtures-v2",
            "source_contract_version": "brain-source-v1",
            "assistant_response_version": "assistant-response-v1",
            "artifact_version": "verified-solution-v1",
            "original_path": "tests/fixtures/contracts/terminal-v2",
            "change_reason": "Task 1.58: run.failed gains code invalid_attachment{reason} — attachment-inspection refusals (corrupt/forged/encrypted/oversized inputs) stop reporting budget_exceeded; failure_class=attachment, recoverable=false, reason is a fixed sub-code with a fixed safe message; Server consumes it for compensation and re-upload guidance.",
            "consumption_policy": "Pin terminal-fixtures-v2 at original_path; changes require a new fixture version and reason.",
            "ownership": {
                "brain": [
                    "snake_case source",
                    "verified_solutions",
                    "selected_page_provenance",
                    "checked source_labels",
                ],
                "server": [
                    "assistantMessageId",
                    "seq",
                    "page boundary events",
                    "commit",
                    "availability",
                    "plainText storage",
                ],
                "browser": [
                    "camelCase public response only",
                    "ignore unknown additive fields; validate known fields",
                ],
            },
            "boundaries": "Fake consumer only; no product commit, replay, permissions or content grounding guarantee.",
            "pairs": [*NAMES, "failure", "failure-invalid-attachment", *review_pairs],
            "change_history": [
                {
                    "date": "2026-10-01",
                    "revision": "218d2de",
                    "reason": "Initial task 1.28 source/browser freeze.",
                },
                {
                    "date": "2026-10-01",
                    "reason": "PR #58 independent review: pre-release v1 correction distinguishes conflict clarification, adds bounded checked existing-solution re-expression references, and makes every discriminated union tag required in both schemas. Existing positive pairs remain unchanged.",
                },
                {
                    "date": "2026-10-10",
                    "reason": "Task 1.58 terminal-fixtures-v2: run.failed adds FailureCode invalid_attachment, FailureClass attachment, and the reason sub-code (AttachmentFailureReason mirrors the file-inspection refusal reasons); invalid_attachment carries a per-reason fixed message and recoverable=false. Prior codes keep v1 meaning; completed/failure pairs are byte-identical.",
                },
            ],
            "existing_reference_policy": "Caller supplies checked ID/version/claim coverage only, never the existing full answer. Public summaries select referenced claims inside assemble_response and retain disclosure checks. Storage/authentication/semantic claim matching are caller responsibilities, not implemented here.",
            "negative_pairs": sorted(
                path.stem for path in (ROOT / "invalid").glob("*.json")
            ),
            "validation_scope": "JSON Schema validates wire structure; Python validators enforce reference/disposition/coverage integrity and the invalid_attachment reason↔message↔class agreement. Browser cannot validate omitted internal artifacts or selected provenance; unknown additions are ignored.",
            "fixture_inputs": "Copied publication-v1 requests are embedded and frozen in each pair; no evals oracle or answer metadata is used.",
        },
    )


if __name__ == "__main__":
    build()
