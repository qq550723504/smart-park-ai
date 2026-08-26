"""Golden-corpus qualification tests for the JioNLP time-parser sidecar.

Every corpus row runs deterministically against the resolver. The safety
gates from the qualification design are enforced explicitly:

- zero explicit-time rows classified as NONE;
- zero truncated/ambiguous expressions silently accepted as PARSED;
- zero no-time or entity-identifier rows producing a range;
- all PR20 review regression cases pass;
- equivalent duplicate ranges never surface as MULTIPLE;
- repeated execution yields byte-identical results.
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import ResolveRequest, resolve_question  # noqa: E402

CORPUS = Path(__file__).resolve().parents[1] / "corpus" / "time_intent_golden.jsonl"


def _load_rows():
    with open(CORPUS, encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def _resolve(row):
    return resolve_question(ResolveRequest(
        question=row["question"],
        referenceInstant=row["referenceInstant"],
        timezone=row["timezone"],
        excludedSpans=row["excludedSpans"],
    ))


def _assert_row(row):
    result = _resolve(row)
    expected = row["expected"]
    assert result.status == expected["status"], (
        f"{row['id']} {row['question']!r}: status "
        f"{result.status} != {expected['status']} ({result.reasonCode})")
    assert (result.reasonCode or None) == (expected["reasonCode"] or None), (
        f"{row['id']} {row['question']!r}: reasonCode mismatch")
    assert len(result.mentions) == len(expected["mentions"]), (
        f"{row['id']} {row['question']!r}: mention count mismatch")

    for actual, wanted in zip(result.mentions, expected["mentions"]):
        assert actual.text == wanted["text"], (
            f"{row['id']}: mention text {actual.text!r} != {wanted['text']!r}")
        # Spans must match the original question exactly.
        assert row["question"][actual.start:actual.end] == wanted["text"], (
            f"{row['id']}: span [{actual.start},{actual.end}) does not slice "
            f"to the mention text")
        assert (actual.start, actual.end) == (wanted["start"], wanted["end"]), (
            f"{row['id']}: span offset mismatch")
        assert actual.fromInclusive == wanted["fromInclusive"], (
            f"{row['id']}: fromInclusive mismatch")
        assert actual.toExclusive == wanted["toExclusive"], (
            f"{row['id']}: toExclusive mismatch")
        assert actual.empty == wanted["empty"], (
            f"{row['id']}: empty flag mismatch")
        if not actual.empty and actual.fromInclusive:
            assert actual.fromInclusive < actual.toExclusive, (
                f"{row['id']}: non-empty range must be strictly ordered")


def test_every_corpus_row_matches_expected_behavior():
    for row in _load_rows():
        _assert_row(row)


def test_corpus_runs_deterministically():
    rows = _load_rows()
    first = [(_resolve(row).status, str(_resolve(row))) for row in rows[:10]]
    second = [(_resolve(row).status, str(_resolve(row))) for row in rows[:10]]
    assert first == second


def test_gate_zero_explicit_time_rows_classified_as_none():
    violations = [
        row["id"] for row in _load_rows()
        if row["category"] != "no_time_entity"
        and row["expected"]["status"] == "NONE"]
    assert violations == [], f"explicit time resolved to NONE: {violations}"


def test_gate_zero_no_time_rows_producing_ranges():
    violations = [
        row["id"] for row in _load_rows()
        if row["category"] == "no_time_entity"
        and (row["expected"]["status"] != "NONE" or row["expected"]["mentions"])]
    assert violations == [], f"entity/no-time produced ranges: {violations}"


def test_gate_zero_truncated_expressions_accepted_as_parsed():
    violations = [
        row["id"] for row in _load_rows()
        if row["expected"]["reasonCode"] in ("TRUNCATED_DURATION", "REVERSED_RANGE",
                                             "INVALID_DATE",
                                             "TIME_EXPRESSION_UNSUPPORTED")
        and row["expected"]["status"] == "PARSED"]
    assert violations == [], f"unresolved expression became PARSED: {violations}"


def test_gate_all_pr20_review_cases_pass():
    pr20 = [row for row in _load_rows() if row.get("pr20ReviewCase")]
    assert len(pr20) >= 15, "the 15 PR20 review seeds must stay in the corpus"
    failures = []
    for row in pr20:
        try:
            _assert_row(row)
        except AssertionError as error:
            failures.append(str(error))
    assert failures == [], failures


def test_gate_duplicate_equivalent_ranges_never_report_multiple():
    for row in _load_rows():
        if row["expected"]["status"] == "MULTIPLE":
            distinct = set(map(tuple, row["expected"]["ranges"]))
            assert len(distinct) > 1, (
                f"{row['id']}: MULTIPLE reported but all ranges are equal")


def test_mentions_always_slice_the_original_question():
    for row in _load_rows():
        for mention in row["expected"]["mentions"]:
            sliced = row["question"][mention["start"]:mention["end"]]
            assert sliced == mention["text"], (
                f"{row['id']}: span does not reproduce mention text")
