package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.QuestionTokenScanner;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FiniteGrammarTimeIntentProvider implements TimeIntentProvider {

    private static final ZoneId PARK_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern CALENDAR_DATE_RANGE = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{4}-\\d{2}-\\d{2})\\s*(?:到|至|~|～)\\s*"
                    + "(\\d{4}-\\d{2}-\\d{2})(?![A-Za-z0-9_-])");
    private static final Pattern CHINESE_CALENDAR_DATE_RANGE = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{4})年(\\d{1,2})月(\\d{1,2})日\\s*(?:到|至|~|～)\\s*"
                    + "(\\d{4})年(\\d{1,2})月(\\d{1,2})日(?![A-Za-z0-9_-])");
    private static final Pattern FULL_NUMERIC_DATE = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?"
                    + "(?![A-Za-z0-9_-])");
    private static final Pattern YEAR_HALF = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?:(今年|本年|去年)|(\\d{4})年)(上半年|下半年)"
                    + "(?![A-Za-z0-9_-])");
    private static final Pattern DAY_PART = Pattern.compile(
            "(?<![A-Za-z0-9_-])(今天|今日)(上午|下午)(?![A-Za-z0-9_-])");
    private static final Pattern QUALIFIED_MONTH_DAY = Pattern.compile(
            "(?<![A-Za-z0-9_-])(本月|上月)(\\d{1,2})(日|号)(?![A-Za-z0-9_-])");
    private static final Pattern MONTH_DAY = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{1,2})月(\\d{1,2})(日|号)(?![A-Za-z0-9_-])");
    private static final Pattern QUALIFIED_WEEK = Pattern.compile(
            "(?<![A-Za-z0-9_-])(本周|上周)([一二三四五六日天末])(?![A-Za-z0-9_-])");
    private static final Pattern DURATION = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?:过去|最近|近)([0-9]+|[一二两三四五六七八九十百千万]+)"
                    + "(个?小时|个?月|个?季度|个?年|周|星期|天|日)(?![A-Za-z0-9_-])");
    private static final Pattern YEAR_MONTH = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{4})年(\\d{1,2})月(?![A-Za-z0-9_-])");
    private static final Pattern YEAR_ONLY = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{4})年(?![A-Za-z0-9_-])");
    private static final Pattern MONTH_ONLY = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{1,2})月(?![A-Za-z0-9_-])");
    private static final Pattern BASE_PERIOD = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?:上上周|上上月|下周|下月|本季度|上季度|下季度|季度|"
                    + "今年|去年|本年|上半年|下半年|明天|后天|未来|今天|今日|昨天|昨日|前天|"
                    + "本周|上周|本月|上月)(?![A-Za-z0-9_-])");
    private static final Pattern TEMPORAL_CUE = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?:今天|今日|昨天|昨日|前天|明天|后天|本周|上周|下周|"
                    + "本月|上月|下月|本季度|上季度|下季度|今年|本年|去年|上半年|下半年|"
                    + "上午|下午|晚上|凌晨|早上|中午|傍晚|"
                    + "(?:过去|最近|近)(?:[0-9]+|[一二两三四五六七八九十百千万]+)"
                    + "(?:个?小时|个?月|个?季度|个?年|周|星期|天|日)|"
                    + "\\d{1,2}(?:日|号))(?![A-Za-z0-9_-])");

    @Override
    public TimeIntentResult resolve(String question, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }
        String normalized = question == null ? "" : question.strip().toLowerCase(Locale.ROOT);
        List<Candidate> candidates = selectCandidates(normalized);
        List<TimeIntentResult.TimeMention> cues = temporalCues(normalized);
        List<TimeIntentResult.TimeMention> selectedMentions = candidates.stream()
                .map(candidate -> mention(normalized, candidate.start(), candidate.end()))
                .toList();
        if (candidates.isEmpty()) {
            return cues.isEmpty()
                    ? new TimeIntentResult(TimeIntentResult.Status.NONE, List.of(), null, null, "")
                    : unsupported(cues, "时间表达式未被有限语法完整识别");
        }
        List<TimeIntentResult.TimeMention> residualCues = cues.stream()
                .filter(cue -> selectedMentions.stream().noneMatch(selected -> contains(selected, cue)))
                .toList();
        if (!residualCues.isEmpty()) {
            List<TimeIntentResult.TimeMention> mentions = new ArrayList<>(selectedMentions);
            mentions.addAll(residualCues);
            return unsupported(mentions, "时间表达式包含未消费的限定词");
        }
        if (candidates.size() > 1) {
            return new TimeIntentResult(TimeIntentResult.Status.MULTIPLE, selectedMentions,
                    null, null, "原始问题包含多个时间范围");
        }
        try {
            Parsed parsed = parse(candidates.get(0), now);
            return parsed == null
                    ? unsupported(selectedMentions, "时间表达式暂不支持")
                    : new TimeIntentResult(TimeIntentResult.Status.PARSED, selectedMentions,
                            parsed.intent(), parsed.range(), "");
        } catch (DateTimeException | NumberFormatException | ArithmeticException invalidExpression) {
            return unsupported(selectedMentions, "时间表达式无效");
        }
    }

    private static List<Candidate> selectCandidates(String question) {
        List<Candidate> all = new ArrayList<>();
        addMatches(question, CALENDAR_DATE_RANGE, ExpressionKind.CALENDAR_DATE_RANGE, all);
        addMatches(question, CHINESE_CALENDAR_DATE_RANGE, ExpressionKind.CHINESE_CALENDAR_DATE_RANGE, all);
        addMatches(question, FULL_NUMERIC_DATE, ExpressionKind.FULL_NUMERIC_DATE, all);
        addMatches(question, YEAR_HALF, ExpressionKind.YEAR_HALF, all);
        addMatches(question, DAY_PART, ExpressionKind.DAY_PART, all);
        addMatches(question, QUALIFIED_MONTH_DAY, ExpressionKind.QUALIFIED_MONTH_DAY, all);
        addMatches(question, QUALIFIED_WEEK, ExpressionKind.QUALIFIED_WEEK, all);
        addMatches(question, DURATION, ExpressionKind.DURATION, all);
        addMatches(question, YEAR_MONTH, ExpressionKind.YEAR_MONTH, all);
        addMatches(question, YEAR_ONLY, ExpressionKind.YEAR_ONLY, all);
        addMatches(question, MONTH_DAY, ExpressionKind.MONTH_DAY, all);
        addMatches(question, MONTH_ONLY, ExpressionKind.MONTH_ONLY, all);
        addMatches(question, BASE_PERIOD, ExpressionKind.BASE_PERIOD, all);
        all.sort(Comparator.comparingInt(Candidate::start)
                .thenComparing(Comparator.comparingInt(
                        (Candidate candidate) -> candidate.end() - candidate.start()).reversed()));
        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : all) {
            if (selected.stream().noneMatch(existing -> overlaps(existing, candidate))) {
                selected.add(candidate);
            }
        }
        selected.sort(Comparator.comparingInt(Candidate::start));
        return selected;
    }

    private static void addMatches(String question, Pattern pattern, ExpressionKind kind,
                                   List<Candidate> candidates) {
        Matcher matcher = pattern.matcher(question);
        while (matcher.find()) {
            if (isDateKind(kind) && !QuestionTokenScanner.isStandaloneSpan(
                    question, matcher.start(), matcher.end())) {
                continue;
            }
            candidates.add(new Candidate(matcher.group(), matcher.start(), matcher.end(), kind));
        }
    }

    private static List<TimeIntentResult.TimeMention> temporalCues(String question) {
        List<TimeIntentResult.TimeMention> cues = new ArrayList<>();
        Matcher matcher = TEMPORAL_CUE.matcher(question);
        while (matcher.find()) {
            if (looksLikeDate(matcher.group())
                    && !QuestionTokenScanner.isStandaloneSpan(question, matcher.start(), matcher.end())) {
                continue;
            }
            cues.add(mention(question, matcher.start(), matcher.end()));
        }
        return cues;
    }

    private static boolean looksLikeDate(String expression) {
        return expression.matches(".*\\d{1,4}年.*|\\d{1,2}(?:日|号)");
    }

    private static Parsed parse(Candidate candidate, Instant now) {
        String expression = candidate.text();
        return switch (candidate.kind()) {
            case CALENDAR_DATE_RANGE -> parseCalendarDateRange(expression);
            case CHINESE_CALENDAR_DATE_RANGE -> parseChineseCalendarDateRange(expression);
            case FULL_NUMERIC_DATE -> parseFullDate(expression);
            case YEAR_HALF -> parseYearHalf(expression, now);
            case DAY_PART -> parseDayPart(expression, now);
            case QUALIFIED_MONTH_DAY -> parseQualifiedMonthDay(expression, now);
            case QUALIFIED_WEEK -> parseQualifiedWeek(expression, now);
            case DURATION -> parseDuration(expression, now);
            case YEAR_MONTH -> parseYearMonth(expression);
            case YEAR_ONLY -> parseYearOnly(expression);
            case MONTH_DAY -> parseMonthDay(expression, now);
            case MONTH_ONLY -> parseMonthOnly(expression, now);
            case BASE_PERIOD -> parseBasePeriod(expression, now);
        };
    }

    private static Parsed parseCalendarDateRange(String expression) {
        Matcher matcher = CALENDAR_DATE_RANGE.matcher(expression);
        matcher.matches();
        return dateRange(LocalDate.parse(matcher.group(1)), LocalDate.parse(matcher.group(2)));
    }

    private static Parsed parseChineseCalendarDateRange(String expression) {
        Matcher matcher = CHINESE_CALENDAR_DATE_RANGE.matcher(expression);
        matcher.matches();
        return dateRange(LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))),
                LocalDate.of(Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)),
                        Integer.parseInt(matcher.group(6))));
    }

    private static Parsed parseFullDate(String expression) {
        Matcher matcher = FULL_NUMERIC_DATE.matcher(expression);
        matcher.matches();
        return singleDate(LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))));
    }

    private static Parsed parseYearHalf(String expression, Instant now) {
        Matcher matcher = YEAR_HALF.matcher(expression);
        matcher.matches();
        int year = matcher.group(2) != null ? Integer.parseInt(matcher.group(2))
                : now.atZone(PARK_ZONE).getYear() - ("去年".equals(matcher.group(1)) ? 1 : 0);
        boolean firstHalf = "上半年".equals(matcher.group(3));
        LocalDate from = LocalDate.of(year, firstHalf ? 1 : 7, 1);
        return calendarPeriod(expression, from, from.plusMonths(6));
    }

    private static Parsed parseDayPart(String expression, Instant now) {
        Matcher matcher = DAY_PART.matcher(expression);
        matcher.matches();
        LocalDate today = now.atZone(PARK_ZONE).toLocalDate();
        TimeIntent.DayPart dayPart = "上午".equals(matcher.group(2))
                ? TimeIntent.DayPart.MORNING : TimeIntent.DayPart.AFTERNOON;
        Instant from = atStartOfDay(today).plusSeconds(dayPart == TimeIntent.DayPart.MORNING ? 0 : 12 * 3600L);
        Instant to = atStartOfDay(today).plusSeconds(dayPart == TimeIntent.DayPart.MORNING ? 12 * 3600L : 24 * 3600L);
        return new Parsed(new TimeIntent(expression, TimeIntent.Kind.DAY_PART, 0, null, today, null, dayPart),
                new QueryPlan.TimeRange(from, to));
    }

    private static Parsed parseQualifiedMonthDay(String expression, Instant now) {
        Matcher matcher = QUALIFIED_MONTH_DAY.matcher(expression);
        matcher.matches();
        LocalDate currentMonth = now.atZone(PARK_ZONE).toLocalDate().withDayOfMonth(1);
        LocalDate month = "上月".equals(matcher.group(1)) ? currentMonth.minusMonths(1) : currentMonth;
        return singleDate(expression, month.withDayOfMonth(Integer.parseInt(matcher.group(2))));
    }

    private static Parsed parseQualifiedWeek(String expression, Instant now) {
        Matcher matcher = QUALIFIED_WEEK.matcher(expression);
        matcher.matches();
        LocalDate today = now.atZone(PARK_ZONE).toLocalDate();
        LocalDate currentWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekStart = "上周".equals(matcher.group(1))
                ? currentWeekStart.minusWeeks(1) : currentWeekStart;
        if ("末".equals(matcher.group(2))) {
            return dateRange(expression, weekStart.plusDays(5), weekStart.plusDays(6));
        }
        int offset = switch (matcher.group(2)) {
            case "一" -> 0;
            case "二" -> 1;
            case "三" -> 2;
            case "四" -> 3;
            case "五" -> 4;
            case "六" -> 5;
            case "日", "天" -> 6;
            default -> throw new IllegalArgumentException("invalid week day");
        };
        LocalDate day = weekStart.plusDays(offset);
        return singleDate(expression, day);
    }

    private static Parsed parseDuration(String expression, Instant now) {
        Matcher matcher = DURATION.matcher(expression);
        matcher.matches();
        long count = parseNumber(matcher.group(1));
        String unit = matcher.group(2);
        if (count <= 0) {
            return null;
        }
        QueryPlan.TimeRange range = switch (unit) {
            case "小时", "个小时" -> new QueryPlan.TimeRange(
                    now.minusSeconds(Math.multiplyExact(count, 3_600)), now);
            case "天", "日" -> new QueryPlan.TimeRange(
                    now.minusSeconds(Math.multiplyExact(count, 86_400)), now);
            case "周", "星期" -> new QueryPlan.TimeRange(
                    now.minusSeconds(Math.multiplyExact(count, 7 * 86_400)), now);
            case "月", "个月" -> new QueryPlan.TimeRange(now.atZone(PARK_ZONE).minusMonths(count).toInstant(), now);
            case "季度", "个季度" -> new QueryPlan.TimeRange(
                    now.atZone(PARK_ZONE).minusMonths(Math.multiplyExact(count, 3)).toInstant(), now);
            case "年", "个年" -> new QueryPlan.TimeRange(now.atZone(PARK_ZONE).minusYears(count).toInstant(), now);
            default -> null;
        };
        if (range == null) {
            return null;
        }
        TimeIntent.Unit intentUnit = switch (unit) {
            case "小时", "个小时" -> TimeIntent.Unit.HOUR;
            case "天", "日" -> TimeIntent.Unit.DAY;
            case "周", "星期" -> TimeIntent.Unit.WEEK;
            case "月", "个月" -> TimeIntent.Unit.MONTH;
            case "季度", "个季度" -> TimeIntent.Unit.QUARTER;
            case "年", "个年" -> TimeIntent.Unit.YEAR;
            default -> throw new IllegalArgumentException("invalid duration unit");
        };
        return new Parsed(new TimeIntent(expression, TimeIntent.Kind.ROLLING, count, intentUnit,
                null, null, null), range);
    }

    private static Parsed parseYearMonth(String expression) {
        Matcher matcher = YEAR_MONTH.matcher(expression);
        matcher.matches();
        LocalDate from = LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), 1);
        return calendarPeriod(expression, from, from.plusMonths(1));
    }

    private static Parsed parseYearOnly(String expression) {
        Matcher matcher = YEAR_ONLY.matcher(expression);
        matcher.matches();
        LocalDate from = LocalDate.of(Integer.parseInt(matcher.group(1)), 1, 1);
        return calendarPeriod(expression, from, from.plusYears(1));
    }

    private static Parsed parseMonthDay(String expression, Instant now) {
        Matcher matcher = MONTH_DAY.matcher(expression);
        matcher.matches();
        return singleDate(expression, LocalDate.of(now.atZone(PARK_ZONE).getYear(),
                Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
    }

    private static Parsed parseMonthOnly(String expression, Instant now) {
        Matcher matcher = MONTH_ONLY.matcher(expression);
        matcher.matches();
        int year = now.atZone(PARK_ZONE).getYear();
        LocalDate from = LocalDate.of(year, Integer.parseInt(matcher.group(1)), 1);
        return calendarPeriod(expression, from, from.plusMonths(1));
    }

    private static Parsed parseBasePeriod(String expression, Instant now) {
        LocalDate today = now.atZone(PARK_ZONE).toLocalDate();
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate monthStart = today.withDayOfMonth(1);
        int quarterMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
        LocalDate quarterStart = LocalDate.of(today.getYear(), quarterMonth, 1);
        return switch (expression) {
            case "今天", "今日" -> new Parsed(
                    new TimeIntent(expression, TimeIntent.Kind.CALENDAR_PERIOD, 0, null, today, today, null),
                    new QueryPlan.TimeRange(atStartOfDay(today), now));
            case "昨天", "昨日" -> calendarPeriod(expression, today.minusDays(1), today);
            case "前天" -> calendarPeriod(expression, today.minusDays(2), today.minusDays(1));
            case "明天" -> calendarPeriod(expression, today.plusDays(1), today.plusDays(2));
            case "后天" -> calendarPeriod(expression, today.plusDays(2), today.plusDays(3));
            case "本周" -> new Parsed(
                    new TimeIntent(expression, TimeIntent.Kind.CALENDAR_PERIOD, 0, null, weekStart, today, null),
                    new QueryPlan.TimeRange(atStartOfDay(weekStart), now));
            case "上周" -> calendarPeriod(expression, weekStart.minusWeeks(1), weekStart);
            case "下周" -> calendarPeriod(expression, weekStart.plusWeeks(1), weekStart.plusWeeks(2));
            case "本月" -> new Parsed(
                    new TimeIntent(expression, TimeIntent.Kind.CALENDAR_PERIOD, 0, null, monthStart, today, null),
                    new QueryPlan.TimeRange(atStartOfDay(monthStart), now));
            case "上月" -> calendarPeriod(expression, monthStart.minusMonths(1), monthStart);
            case "下月" -> calendarPeriod(expression, monthStart.plusMonths(1), monthStart.plusMonths(2));
            case "本季度" -> new Parsed(
                    new TimeIntent(expression, TimeIntent.Kind.CALENDAR_PERIOD, 0, null, quarterStart, today, null),
                    new QueryPlan.TimeRange(atStartOfDay(quarterStart), now));
            case "上季度" -> calendarPeriod(expression, quarterStart.minusMonths(3), quarterStart);
            case "下季度" -> calendarPeriod(expression, quarterStart.plusMonths(3), quarterStart.plusMonths(6));
            case "今年", "本年" -> new Parsed(
                    new TimeIntent(expression, TimeIntent.Kind.CALENDAR_PERIOD, 0, null,
                            LocalDate.of(today.getYear(), 1, 1), today, null),
                    new QueryPlan.TimeRange(atStartOfDay(LocalDate.of(today.getYear(), 1, 1)), now));
            case "去年" -> calendarPeriod(expression, LocalDate.of(today.getYear() - 1, 1, 1),
                    LocalDate.of(today.getYear(), 1, 1));
            case "上半年" -> calendarPeriod(expression, LocalDate.of(today.getYear(), 1, 1),
                    LocalDate.of(today.getYear(), 7, 1));
            case "下半年" -> calendarPeriod(expression, LocalDate.of(today.getYear(), 7, 1),
                    LocalDate.of(today.getYear() + 1, 1, 1));
            default -> null;
        };
    }

    private static Parsed singleDate(LocalDate date) {
        return singleDate(date.toString(), date);
    }

    private static Parsed singleDate(String expression, LocalDate date) {
        return new Parsed(new TimeIntent(expression, TimeIntent.Kind.SINGLE_DATE, 0, null, date, null, null),
                new QueryPlan.TimeRange(atStartOfDay(date), atStartOfDay(date.plusDays(1))));
    }

    private static Parsed dateRange(LocalDate from, LocalDate toInclusive) {
        return dateRange(from + "到" + toInclusive, from, toInclusive);
    }

    private static Parsed dateRange(String expression, LocalDate from, LocalDate toInclusive) {
        if (toInclusive.isBefore(from)) {
            return null;
        }
        return new Parsed(new TimeIntent(expression, TimeIntent.Kind.DATE_RANGE, 0, null, from, toInclusive, null),
                new QueryPlan.TimeRange(atStartOfDay(from), atStartOfDay(toInclusive.plusDays(1))));
    }

    private static Parsed calendarPeriod(String expression, LocalDate from, LocalDate toExclusive) {
        if (toExclusive.isBefore(from)) {
            return null;
        }
        return new Parsed(new TimeIntent(expression, TimeIntent.Kind.CALENDAR_PERIOD, 0, null,
                        from, toExclusive, null),
                new QueryPlan.TimeRange(atStartOfDay(from), atStartOfDay(toExclusive)));
    }

    private static TimeIntentResult unsupported(List<TimeIntentResult.TimeMention> mentions, String reason) {
        return new TimeIntentResult(TimeIntentResult.Status.UNSUPPORTED, mentions, null, null, reason);
    }

    private static TimeIntentResult.TimeMention mention(String question, int start, int end) {
        return new TimeIntentResult.TimeMention(question.substring(start, end), start, end);
    }

    private static boolean contains(TimeIntentResult.TimeMention outer, TimeIntentResult.TimeMention inner) {
        return outer.start() <= inner.start() && outer.end() >= inner.end();
    }

    private static boolean overlaps(Candidate left, Candidate right) {
        return left.start() < right.end() && right.start() < left.end();
    }

    private static boolean isDateKind(ExpressionKind kind) {
        return kind == ExpressionKind.CALENDAR_DATE_RANGE
                || kind == ExpressionKind.CHINESE_CALENDAR_DATE_RANGE
                || kind == ExpressionKind.FULL_NUMERIC_DATE
                || kind == ExpressionKind.YEAR_MONTH
                || kind == ExpressionKind.YEAR_ONLY
                || kind == ExpressionKind.MONTH_DAY
                || kind == ExpressionKind.MONTH_ONLY;
    }

    private static Instant atStartOfDay(LocalDate date) {
        return date.atStartOfDay(PARK_ZONE).toInstant();
    }

    private static long parseNumber(String value) {
        if (value.chars().allMatch(Character::isDigit)) {
            return Long.parseLong(value);
        }
        int total = 0;
        int section = 0;
        int number = 0;
        for (char character : value.toCharArray()) {
            int digit = chineseDigit(character);
            if (digit >= 0) {
                number = digit;
                continue;
            }
            int unit = chineseUnit(character);
            if (unit < 10_000) {
                section += (number == 0 ? 1 : number) * unit;
                number = 0;
            } else {
                section += number;
                total += section * unit;
                section = 0;
                number = 0;
            }
        }
        return total + section + number;
    }

    private static int chineseDigit(char character) {
        return switch (character) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    private static int chineseUnit(char character) {
        return switch (character) {
            case '十' -> 10;
            case '百' -> 100;
            case '千' -> 1_000;
            case '万' -> 10_000;
            default -> -1;
        };
    }

    private record Candidate(String text, int start, int end, ExpressionKind kind) {
    }

    private record Parsed(TimeIntent intent, QueryPlan.TimeRange range) {
    }

    private enum ExpressionKind {
        CALENDAR_DATE_RANGE, CHINESE_CALENDAR_DATE_RANGE, FULL_NUMERIC_DATE,
        YEAR_HALF, DAY_PART, QUALIFIED_MONTH_DAY, QUALIFIED_WEEK, DURATION,
        YEAR_MONTH, YEAR_ONLY, MONTH_DAY, MONTH_ONLY, BASE_PERIOD
    }
}
