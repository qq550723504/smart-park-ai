"""HTTP contract tests for the JioNLP time-parser sidecar."""

import json
import sys
from pathlib import Path

from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import app  # noqa: E402

client = TestClient(app)

REFERENCE = "2026-08-25T00:00:00Z"


def _resolve(payload):
    return client.post("/v1/time-intents:resolve", json=payload)


def test_health_reports_up_provider_and_qualified_version():
    response = client.get("/healthz")

    assert response.status_code == 200
    body = response.json()
    assert body == {"status": "UP", "provider": "jionlp", "version": "1.5.29"}


def test_resolve_returns_parsed_mention_with_exact_spans_and_half_open_range():
    response = _resolve({
        "question": "上周一到周三能耗",
        "referenceInstant": REFERENCE,
        "timezone": "Asia/Shanghai",
        "excludedSpans": [],
    })

    assert response.status_code == 200
    body = response.json()
    assert body["provider"] == "jionlp"
    assert body["providerVersion"] == "1.5.29"
    assert body["referenceInstant"] == REFERENCE
    assert body["timezone"] == "Asia/Shanghai"
    assert body["status"] == "PARSED"
    assert body["reasonCode"] is None
    [mention] = body["mentions"]
    assert mention["text"] == "上周一到周三"
    assert mention["start"] == 0
    assert mention["end"] == 6
    assert mention["fromInclusive"] == "2026-08-16T16:00:00Z"
    assert mention["toExclusive"] == "2026-08-19T16:00:00Z"
    assert mention["empty"] is False


def test_excluded_entity_span_yields_none_without_mentions():
    response = _resolve({
        "question": "MTR-2026-08-01表计的能耗",
        "referenceInstant": REFERENCE,
        "timezone": "Asia/Shanghai",
        "excludedSpans": [{"start": 0, "end": 12}],
    })

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "NONE"
    assert body["mentions"] == []


def test_unsupported_timezone_is_rejected_with_stable_reason_code():
    response = _resolve({
        "question": "今天能耗",
        "referenceInstant": REFERENCE,
        "timezone": "UTC",
        "excludedSpans": [],
    })

    assert response.status_code == 400
    detail = response.json()["detail"]
    assert detail["reasonCode"] == "UNSUPPORTED_TIMEZONE"


def test_invalid_reference_instant_is_rejected():
    response = _resolve({
        "question": "今天能耗",
        "referenceInstant": "not-an-instant",
        "timezone": "Asia/Shanghai",
        "excludedSpans": [],
    })

    assert response.status_code == 400
    assert response.json()["detail"]["reasonCode"] == "INVALID_REFERENCE_INSTANT"


def test_out_of_bounds_excluded_span_is_rejected():
    response = _resolve({
        "question": "今天能耗",  # 4 code points
        "referenceInstant": REFERENCE,
        "timezone": "Asia/Shanghai",
        "excludedSpans": [{"start": 2, "end": 9}],
    })

    assert response.status_code == 400
    assert response.json()["detail"]["reasonCode"] == "INVALID_EXCLUDED_SPAN"


def test_empty_current_period_reports_empty_with_equal_boundaries():
    # 2026-08-24T16:00:00Z is exactly 2026-08-25 00:00 Asia/Shanghai.
    response = _resolve({
        "question": "今天能耗",
        "referenceInstant": "2026-08-24T16:00:00Z",
        "timezone": "Asia/Shanghai",
        "excludedSpans": [],
    })

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "EMPTY"
    assert body["reasonCode"] is None
    [mention] = body["mentions"]
    assert mention["empty"] is True
    assert mention["fromInclusive"] == mention["toExclusive"]
    assert mention["fromInclusive"] == "2026-08-24T16:00:00Z"


def test_offsets_are_unicode_code_points_not_utf16_units():
    # 🔔 is one code point but two UTF-16 units: the mention span after it
    # must still be reported in code points.
    response = _resolve({
        "question": "🔔本周能耗",
        "referenceInstant": REFERENCE,
        "timezone": "Asia/Shanghai",
        "excludedSpans": [],
    })

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "PARSED"
    [mention] = body["mentions"]
    assert mention["text"] == "本周"
    assert mention["start"] == 1  # code point index, not UTF-16 unit
    assert mention["end"] == 3


def test_repeated_calls_are_deterministic():
    payload = {
        "question": "过去两周能耗对比",
        "referenceInstant": REFERENCE,
        "timezone": "Asia/Shanghai",
        "excludedSpans": [],
    }

    first = _resolve(payload).content
    second = _resolve(payload).content
    third = _resolve(payload).content

    assert first == second == third


def test_error_responses_never_leak_stack_details():
    response = _resolve({
        "question": "今天能耗",
        "referenceInstant": "2026-13-99T99:00:00Z",
        "timezone": "Asia/Shanghai",
        "excludedSpans": [],
    })

    assert response.status_code == 400
    serialized = json.dumps(response.json())
    assert "Traceback" not in serialized
    assert "jionlp" not in serialized.lower() or \
        response.json()["detail"].get("reasonCode")
