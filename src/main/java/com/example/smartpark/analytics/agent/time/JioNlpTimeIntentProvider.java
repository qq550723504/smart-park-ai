package com.example.smartpark.analytics.agent.time;

import com.example.smartpark.analytics.agent.TimeIntent;
import com.example.smartpark.analytics.agent.TimeIntentProvider;
import com.example.smartpark.analytics.agent.TimeIntentResult;
import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.QuestionTokenScanner;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Maps the sidecar contract to the Java-owned time intent boundary. */
public final class JioNlpTimeIntentProvider implements TimeIntentProvider {

    private static final ZoneId PARK_ZONE = ZoneId.of("Asia/Shanghai");
    private final Function<TimeParserRequest, TimeParserResponse> resolver;
    private final String timezone;

    public JioNlpTimeIntentProvider(JioNlpClient client) {
        this(Objects.requireNonNull(client, "client")::resolve, "Asia/Shanghai");
    }

    JioNlpTimeIntentProvider(Function<TimeParserRequest, TimeParserResponse> resolver) {
        this(resolver, "Asia/Shanghai");
    }

    public JioNlpTimeIntentProvider(JioNlpClient client, String timezone) {
        this(Objects.requireNonNull(client, "client")::resolve, timezone);
    }

    JioNlpTimeIntentProvider(Function<TimeParserRequest, TimeParserResponse> resolver, String timezone) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        if (!"Asia/Shanghai".equals(timezone)) {
            throw new IllegalArgumentException("only Asia/Shanghai is supported");
        }
        this.timezone = timezone;
    }

    @Override
    public TimeIntentResult resolve(String question, Instant now) {
        if (question == null || question.isBlank() || now == null) {
            throw new IllegalArgumentException("question and now are required");
        }
        List<UnicodeOffsetMapper.Span> excluded = QuestionTokenScanner.entityIdentifiers(question).stream()
                .map(token -> UnicodeOffsetMapper.toCodePoints(question, token.start(), token.end()))
                .toList();
        TimeParserRequest request = new TimeParserRequest(question, now.toString(), timezone, excluded);
        TimeParserResponse response = resolver.apply(request);
        List<QuestionTokenScanner.Token> entities = QuestionTokenScanner.entityIdentifiers(question);
        List<TimeIntentResult.TimeMention> mentions = new ArrayList<>();
        List<Range> ranges = new ArrayList<>();
        for (TimeParserMention mention : response.mentions()) {
            UnicodeOffsetMapper.Span utf16 = UnicodeOffsetMapper.toUtf16(question, mention.start(), mention.end());
            if (!question.substring(utf16.start(), utf16.end()).equals(mention.text())) {
                throw new TimeParserInvalidResponseException("parser mention text does not match question");
            }
            if (entities.stream().anyMatch(entity -> utf16.start() < entity.end()
                    && entity.start() < utf16.end())) {
                throw new TimeParserInvalidResponseException("parser mention overlaps entity identifier");
            }
            mentions.add(new TimeIntentResult.TimeMention(mention.text(), utf16.start(), utf16.end()));
            if (mention.fromInclusive() != null) {
                try {
                    ranges.add(new Range(Instant.parse(mention.fromInclusive()), Instant.parse(mention.toExclusive()), mention.empty()));
                } catch (RuntimeException invalid) {
                    throw new TimeParserInvalidResponseException("parser range is not ISO-8601", invalid);
                }
            }
        }
        if (response.status().equals("NONE") || response.status().equals("UNSUPPORTED")
                || response.status().equals("AMBIGUOUS") || response.status().equals("MULTIPLE")) {
            return new TimeIntentResult(TimeIntentResult.Status.valueOf(response.status()), mentions, null, null,
                    response.reasonCode() == null ? "" : response.reasonCode());
        }
        if (ranges.isEmpty()) {
            throw new TimeParserInvalidResponseException("parsed parser response has no range");
        }
        List<RangeKey> distinct = ranges.stream()
                .map(range -> new RangeKey(range.from(), range.to()))
                .distinct().toList();
        if (distinct.size() > 1) {
            return new TimeIntentResult(TimeIntentResult.Status.MULTIPLE, mentions, null, null,
                    "MULTIPLE_DISTINCT_RANGES");
        }
        RangeKey range = distinct.get(0);
        QueryPlan.TimeRange timeRange = new QueryPlan.TimeRange(range.from(), range.to());
        TimeIntent intent = intentFor(mentions.isEmpty() ? "时间范围" : mentions.get(0).text(), range);
        boolean empty = ranges.stream().anyMatch(candidate -> candidate.from().equals(range.from())
                && candidate.to().equals(range.to()) && candidate.empty());
        TimeIntentResult.Status status = response.status().equals("EMPTY") || empty
                ? TimeIntentResult.Status.EMPTY : TimeIntentResult.Status.PARSED;
        if (status == TimeIntentResult.Status.EMPTY && !range.from().equals(range.to())) {
            throw new TimeParserInvalidResponseException("EMPTY parser response must be zero-width");
        }
        return new TimeIntentResult(status, mentions, intent, timeRange,
                response.reasonCode() == null ? "" : response.reasonCode());
    }

    private static TimeIntent intentFor(String source, RangeKey range) {
        var fromDate = range.from().atZone(PARK_ZONE).toLocalDate();
        var toDate = range.to().atZone(PARK_ZONE).toLocalDate();
        if (range.from().equals(range.to())) {
            return new TimeIntent(source, TimeIntent.Kind.CALENDAR_PERIOD, 0, null, fromDate, fromDate, null);
        }
        return new TimeIntent(source, TimeIntent.Kind.DATE_RANGE, 0, null, fromDate, toDate, null);
    }

    private record Range(Instant from, Instant to, boolean empty) {
        private Range {
            if (from == null || to == null || to.isBefore(from)) {
                throw new TimeParserInvalidResponseException("parser range is unordered");
            }
        }
    }

    private record RangeKey(Instant from, Instant to) {
    }
}
