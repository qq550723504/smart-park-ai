"""Internal JioNLP time-intent parsing sidecar.

Exposes a single versioned HTTP contract used by the smartpark analytics
backend. Recognition comes from JioNLP; this service adds three fixed,
bounded safety rules around it (no growing grammar):

1. Truncation guard   -- a trailing ``半`` + unit that JioNLP silently drops
                         (近一年半 -> 近一年) makes the mention UNSUPPORTED.
2. Composition        -- ``A到B`` mentions parse both endpoints against the
                         caller-supplied reference instant; the second
                         endpoint inherits the first segment's relative
                         qualifier (上周一到周三 -> 上周一到上周三).
3. Current periods    -- exact current-period words (今天/本周/本月/...) yield
                         [period_start, referenceInstant); at the exact
                         boundary this is the empty range and reported EMPTY.

All offsets on the wire are Unicode code point indices, end-exclusive.
Only Asia/Shanghai is accepted. No network, database, or model access.
"""

from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone as dt_timezone
from importlib import metadata
from typing import Optional
from zoneinfo import ZoneInfo

import jionlp as jio
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

PROVIDER = "jionlp"
EXPECTED_VERSION = "1.5.29"
ALLOWED_TIMEZONE = "Asia/Shanghai"
PARK_ZONE = ZoneInfo(ALLOWED_TIMEZONE)
MAX_QUESTION_LENGTH = 2_000
MAX_EXCLUDED_SPANS = 64

_installed_version = metadata.version("jionlp")
if _installed_version != EXPECTED_VERSION:  # pragma: no cover - startup guard
    raise RuntimeError(
        f"qualified jionlp version is {EXPECTED_VERSION}, found {_installed_version}"
    )

# Exact current-period words whose data window is capped at the reference
# instant. Closed set: extending it requires requalification, not growth.
CURRENT_PERIODS = {"今天", "今日", "本周", "这一周", "本月", "这个月",
                   "本季度", "今年", "本年"}

# Relative qualifiers that may be inherited by the second endpoint of a
# composed A-to-B mention. Longest-first matching; closed set.
_QUALIFIERS = sorted(
    ["上上周", "上上月", "上上个月", "去年", "上个月", "上月", "上个季度",
     "上季度", "上周", "本月", "本周", "今年", "本年", "本季度", "下个月",
     "下月", "下周", "明年", "上半年", "下半年", "前天", "昨天", "昨日",
     "今天", "今日", "明天", "后天", "上", "下", "本", "这"],
    key=len, reverse=True)

_SPLIT = re.compile(r"到|至|~|～")
# Structured handoffs use exact UTC instants. Treat the complete expression as
# one atomic range before JioNLP sees it; otherwise NER splits each timestamp
# into independent date/time mentions and reports MULTIPLE.
_ISO_RANGE = re.compile(
    r"(?P<from>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?Z)"
    r"\s*(?:至|到|~|～)\s*"
    r"(?P<to>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?Z)"
)
_DATE_SHAPES = [
    re.compile(r"(?<![\d])(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?![\d])"),
    re.compile(r"(\d{4})年(\d{1,2})月(\d{1,2})[日号]"),
    re.compile(r"(?<![\d])(\d{1,2})月(\d{1,2})[日号](?![\d])"),
]
_HALF_UNIT_CHARS = {"年": 1, "月": 1, "周": 1, "天": 1, "日": 1, "号": 1,
                    "时": 1, "点": 1, "季": 1}


class UnsupportedTimezoneError(Exception):
    pass


class Span(BaseModel):
    start: int = Field(ge=0)
    end: int = Field(gt=0)


class ResolveRequest(BaseModel):
    question: str = Field(min_length=1, max_length=MAX_QUESTION_LENGTH)
    referenceInstant: str
    timezone: str
    excludedSpans: list[Span] = Field(default_factory=list,
                                      max_length=MAX_EXCLUDED_SPANS)


class Mention(BaseModel):
    text: str
    start: int
    end: int
    type: Optional[str] = None
    definition: Optional[str] = None
    fromInclusive: Optional[str] = None
    toExclusive: Optional[str] = None
    empty: bool = False


class ResolveResponse(BaseModel):
    provider: str
    providerVersion: str
    referenceInstant: str
    timezone: str
    status: str
    mentions: list[Mention]
    reasonCode: Optional[str] = None


class HealthResponse(BaseModel):
    status: str
    provider: str
    version: str


def _utc(value: datetime) -> str:
    return value.astimezone(dt_timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _parse_reference(raw: str) -> tuple[datetime, str]:
    """Returns (aware instant, canonical UTC string) or raises ValueError."""
    text = raw.strip()
    try:
        moment = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError("referenceInstant is not ISO-8601") from error
    if moment.tzinfo is None:
        raise ValueError("referenceInstant must carry a UTC offset")
    return moment.astimezone(dt_timezone.utc), _utc(moment)


def _base_string(moment: datetime) -> str:
    """JioNLP parse base in park-local wall clock (naive)."""
    local = moment.astimezone(PARK_ZONE).replace(tzinfo=None)
    return local.strftime("%Y-%m-%d %H:%M:%S")


def _to_utc(naive: datetime) -> datetime:
    return naive.replace(tzinfo=PARK_ZONE).astimezone(dt_timezone.utc)


def _qualifier(segment: str) -> Optional[str]:
    for qualifier in _QUALIFIERS:
        if segment.startswith(qualifier) and len(segment) > len(qualifier):
            return qualifier
    return None


def has_invalid_calendar_date(question: str, excluded: list[tuple[int, int]]) -> bool:
    """True when the question carries a date-shaped expression outside every
    excluded span that names an impossible calendar date (2026-02-30)."""

    def overlaps_any(start: int, end: int) -> bool:
        return any(start < ex_end and ex_start < end
                   for ex_start, ex_end in excluded)

    for pattern in _DATE_SHAPES:
        for match in pattern.finditer(question):
            if overlaps_any(match.start(), match.end()):
                continue
            groups = [int(group) for group in match.groups()]
            year = groups[0] if len(groups) == 3 else _assumed_year()
            month, day = groups[-2], groups[-1]
            try:
                datetime(year, month, day)
            except ValueError:
                return True
    return False


def _assumed_year() -> int:
    # Only used by month-day shapes without a year; those cannot be invalid
    # by year, so any leap-consistent choice suffices for February checks.
    return 2024


_ROLLING_PREFIXES = ("过去", "最近", "近", "未来")


def _extend_truncated(question: str, start: int, end: int) -> tuple[int, bool]:
    """Detects a trailing ``半`` quantity that JioNLP silently drops.

    JioNLP parses 近一年半 as 近一年. When a rolling-duration mention is
    immediately followed by ``半``, the expression is a compound half-unit
    quantity the qualified parser must refuse instead of truncating.
    """
    if (end < len(question) and question[end] == "半"
            and question[start:end].startswith(_ROLLING_PREFIXES)):
        return end, True
    return end, False


def _parse_endpoint(segment: str, inherited: Optional[str], base: str):
    """Parses one composed endpoint.

    A bare endpoint (周三) inherits the first segment's relative qualifier
    (上周) before parsing, otherwise it would resolve against the wrong week.
    """
    if inherited is not None and _qualifier(segment) is None:
        composed = _safe_parse(inherited + segment, base)
        if composed is not None:
            return composed
    return _safe_parse(segment, base)


def _safe_parse(text: str, base: str):
    try:
        return jio.parse_time(text, time_base=base)
    except Exception:
        return None


def _resolve_mention(question: str, start: int, end: int, base: str,
                     reference: datetime) -> tuple[Mention, Optional[str]]:
    """Resolves one mention span.

    Returns (mention, failure reasonCode). A mention carries a range only
    when resolution succeeded.
    """
    def _window(parsed) -> Optional[list[str]]:
        window = parsed.get("time") if isinstance(parsed, dict) else None
        if (isinstance(window, list) and len(window) == 2
                and all(isinstance(item, str) for item in window)):
            return window
        return None

    text = question[start:end]
    parts = _SPLIT.split(text, maxsplit=1)
    detail = None
    if len(parts) == 2 and all(part.strip() for part in parts):
        first, second = parts
        parsed_first = _safe_parse(first, base)
        parsed_second = _parse_endpoint(second, _qualifier(first), base)
        if parsed_first is not None and parsed_second is not None \
                and _window(parsed_first) and _window(parsed_second):
            begin = datetime.strptime(parsed_first["time"][0], "%Y-%m-%d %H:%M:%S")
            finish = datetime.strptime(parsed_second["time"][1], "%Y-%m-%d %H:%M:%S")
            if begin >= finish:
                return (_plain_mention(question, start, end), "REVERSED_RANGE")
            detail = {"type": parsed_first.get("type", "time_span"),
                      "definition": parsed_first.get("definition", "accurate"),
                      "time": [parsed_first["time"][0], parsed_second["time"][1]]}
    if detail is None:
        parsed = _safe_parse(text, base)
        if _window(parsed) is None:
            return (_plain_mention(question, start, end),
                    "TIME_EXPRESSION_UNSUPPORTED")
        detail = parsed

    if _window(detail) is None:
        return (_plain_mention(question, start, end),
                "TIME_EXPRESSION_UNSUPPORTED")
    begin = datetime.strptime(detail["time"][0], "%Y-%m-%d %H:%M:%S")
    finish_inclusive = datetime.strptime(detail["time"][1], "%Y-%m-%d %H:%M:%S")
    from_inclusive = _to_utc(begin)
    to_exclusive = _to_utc(finish_inclusive + timedelta(seconds=1))
    # Rolling spans end inclusively at the base instant; normalize the
    # convention's +1s back onto the exact reference instant.
    skew = (to_exclusive - reference).total_seconds()
    if 0 < skew <= 5:
        to_exclusive = reference

    if text in CURRENT_PERIODS:
        # The data window of an ongoing period ends at the reference instant;
        # at the exact period boundary this collapses to [t, t) -> EMPTY.
        now_local = reference.astimezone(PARK_ZONE).replace(tzinfo=None)
        to_exclusive = min(to_exclusive, _to_utc(now_local))
    empty = from_inclusive >= to_exclusive
    if empty:
        from_inclusive = to_exclusive

    mention = Mention(
        text=text, start=start, end=end,
        type=detail.get("type"), definition=detail.get("definition"),
        fromInclusive=_utc(from_inclusive), toExclusive=_utc(to_exclusive),
        empty=empty)
    return (mention, None)


def _plain_mention(question: str, start: int, end: int) -> Mention:
    return Mention(text=question[start:end], start=start, end=end)


def resolve_question(request: ResolveRequest) -> ResolveResponse:
    reference, canonical_reference = _parse_reference(request.referenceInstant)
    base = _base_string(reference)
    question = request.question
    excluded = [(span.start, span.end) for span in request.excludedSpans]

    if has_invalid_calendar_date(question, excluded):
        return ResolveResponse(
            provider=PROVIDER, providerVersion=EXPECTED_VERSION,
            referenceInstant=canonical_reference, timezone=request.timezone,
            status="UNSUPPORTED", mentions=[], reasonCode="INVALID_DATE")

    atomic_matches = [match for match in _ISO_RANGE.finditer(question)
                      if not any(match.start() < ex_end and ex_start < match.end()
                                 for ex_start, ex_end in excluded)]
    if atomic_matches:
        mentions = []
        try:
            for atomic in atomic_matches:
                start = datetime.fromisoformat(atomic.group("from").replace("Z", "+00:00"))
                end = datetime.fromisoformat(atomic.group("to").replace("Z", "+00:00"))
                if start >= end:
                    return ResolveResponse(
                        provider=PROVIDER, providerVersion=EXPECTED_VERSION,
                        referenceInstant=canonical_reference, timezone=request.timezone,
                        status="UNSUPPORTED", mentions=[], reasonCode="REVERSED_RANGE")
                mentions.append(Mention(
                    text=atomic.group(0), start=atomic.start(), end=atomic.end(),
                    type="time_point", definition="accurate",
                    fromInclusive=_utc(start), toExclusive=_utc(end), empty=False))
        except ValueError:
            return ResolveResponse(
                provider=PROVIDER, providerVersion=EXPECTED_VERSION,
                referenceInstant=canonical_reference, timezone=request.timezone,
                status="UNSUPPORTED", mentions=[], reasonCode="INVALID_DATE")
        if len({(mention.fromInclusive, mention.toExclusive) for mention in mentions}) > 1:
            return ResolveResponse(
                provider=PROVIDER, providerVersion=EXPECTED_VERSION,
                referenceInstant=canonical_reference, timezone=request.timezone,
                status="MULTIPLE", mentions=mentions, reasonCode="MULTIPLE_DISTINCT_RANGES")
        return ResolveResponse(
            provider=PROVIDER, providerVersion=EXPECTED_VERSION,
            referenceInstant=canonical_reference, timezone=request.timezone,
            status="PARSED", mentions=mentions, reasonCode=None)

    raw_mentions = jio.ner.extract_time(question) or []
    eligible = []
    for item in raw_mentions:
        start, end = item["offset"]
        if any(start < ex_end and ex_start < end for ex_start, ex_end in excluded):
            continue
        eligible.append((item, start, end))

    resolved: list[Mention] = []
    failures: list[str] = []
    for item, start, end in eligible:
        end, truncated = _extend_truncated(question, start, end)
        if truncated:
            resolved.append(_plain_mention(question, start, end))
            failures.append("TRUNCATED_DURATION")
            continue
        mention, failure = _resolve_mention(question, start, end, base, reference)
        resolved.append(mention)
        if failure is not None:
            failures.append(failure)

    ranges = list(_distinct_ranges(resolved))
    if not eligible:
        status, reason = "NONE", None
    elif failures:
        if len(ranges) > 1:
            status, reason = "MULTIPLE", "MULTIPLE_DISTINCT_RANGES"
        else:
            status, reason = "UNSUPPORTED", _priority_reason(failures)
    elif len(ranges) > 1:
        status, reason = "MULTIPLE", "MULTIPLE_DISTINCT_RANGES"
    elif ranges[0][0] == ranges[0][1]:
        status, reason = "EMPTY", None
    else:
        status, reason = "PARSED", None

    return ResolveResponse(
        provider=PROVIDER, providerVersion=EXPECTED_VERSION,
        referenceInstant=canonical_reference, timezone=request.timezone,
        status=status, mentions=resolved, reasonCode=reason)


def _distinct_ranges(mentions: list[Mention]):
    seen = dict.fromkeys((m.fromInclusive, m.toExclusive)
                         for m in mentions if m.fromInclusive and m.toExclusive)
    return seen.keys()


def _priority_reason(failures: list[str]) -> str:
    for candidate in ("REVERSED_RANGE", "TRUNCATED_DURATION",
                      "TIME_EXPRESSION_UNSUPPORTED"):
        if candidate in failures:
            return candidate
    return failures[0]


app = FastAPI(title="smartpark time-parser", docs_url=None, redoc_url=None)


@app.exception_handler(UnsupportedTimezoneError)
async def _timezone_handler(request, exc):  # pragma: no cover - trivial mapping
    raise HTTPException(status_code=400, detail={"reasonCode": "UNSUPPORTED_TIMEZONE"})


@app.get("/healthz", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="UP", provider=PROVIDER, version=EXPECTED_VERSION)


@app.post("/v1/time-intents:resolve", response_model=ResolveResponse)
def resolve(request: ResolveRequest) -> ResolveResponse:
    if request.timezone != ALLOWED_TIMEZONE:
        raise HTTPException(
            status_code=400,
            detail={"reasonCode": "UNSUPPORTED_TIMEZONE",
                    "message": f"only {ALLOWED_TIMEZONE} is supported"})
    try:
        _parse_reference(request.referenceInstant)
    except ValueError as error:
        raise HTTPException(status_code=400, detail={
            "reasonCode": "INVALID_REFERENCE_INSTANT", "message": str(error)})
    length = len(request.question)  # Python str length == code point count
    for span in request.excludedSpans:
        if span.start >= span.end or span.end > length:
            raise HTTPException(status_code=400, detail={
                "reasonCode": "INVALID_EXCLUDED_SPAN",
                "message": f"excluded span [{span.start},{span.end}) violates "
                           f"0 <= start < end <= {length}"})
    return resolve_question(request)
