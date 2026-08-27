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

/**
 * Deterministic whitelist resolver: the only component allowed to turn a
 * recognized time expression into a concrete range. Bounded rule set —
 * extending coverage means adding a tested rule here, never trusting model
 * arithmetic. Expressions outside the whitelist are reported UNSUPPORTED by
 * the reconciler (via unmatched model mentions) instead of silently falling
 * back to a default lookback.
 *
 * Ongoing periods (今天/本周/本月/…) are capped at the reference instant; a
 * reference instant coinciding with the period start collapses to the
 * zero-width EMPTY range rather than a fabricated one-second window.
 */
final class WhitelistTimeIntentProvider implements TimeIntentProvider {

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
            "(?<![A-Za-z0-9_-])(今天|今日)(上午|下午|晚上)(?![A-Za-z0-9_-])");
    private static final Pattern QUALIFIED_MONTH_DAY = Pattern.compile(
            "(?<![A-Za-z0-9_-])(本月|上月)(\\d{1,2})(日|号)(?![A-Za-z0-9_-])");
    private static final Pattern MONTH_DAY_RANGE = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{1,2})月(\\d{1,2})[日号]\\s*(?:到|至|~|～)\\s*"
                    + "(\\d{1,2})月(\\d{1,2})[日号](?![A-Za-z0-9_-])");
    private static final Pattern HOUR_RANGE = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{1,2})[点时](\\d{1,2}分)?\\s*(?:到|至|~|～)\\s*"
                    + "(\\d{1,2})[点时](\\d{1,2}分)?(?![A-Za-z0-9_-])");
    private static final Pattern QUALIFIED_WEEK = Pattern.compile(
            "(?<![A-Za-z0-9_-])(本周|上周)([一二三四五六日天末])(?![A-Za-z0-9_-])");
    private static final Pattern WEEK_RANGE = Pattern.compile(
            "(?<![A-Za-z0-9_-])(本周|上周|下周)?(?:周|星期)?([一二三四五六日天末])\\s*(?:到|至|~|～)\\s*"
                    + "(本周|上周|下周)?(?:周|星期)?([一二三四五六日天末])(?![A-Za-z0-9_-])");
    private static final Pattern DURATION = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?:过去|最近|近)([0-9]+|[一二两三四五六七八九十百千万]+)"
                    + "(个?小时|个?月|个?季度|个?年|周|星期|天|日)(?![A-Za-z0-9_-])");
    private static final Pattern YEAR_MONTH = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{4})年(\\d{1,2})月(?![A-Za-z0-9_-])");
    private static final Pattern YEAR_ONLY = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{4})年(?![A-Za-z0-9_-])");
    private static final Pattern MONTH_DAY = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{1,2})月(\\d{1,2})(日|号)(?![A-Za-z0-9_-])");
    private static final Pattern MONTH_ONLY = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{1,2})月(?![A-Za-z0-9_-])");
    private static final Pattern BASE_PERIOD = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?:上上周|上上月|下周|下月|下个月|本季度|上季度|下季度|"
                    + "今年|去年|本年|上半年|下半年|明天|后天|未来|今天|今日|昨天|昨日|前天|"
                    + "本周|上周|本月|这个月|这个季度)(?![A-Za-z0-9_-])");

    @Override
    public TimeIntentResult resolve(String question, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }
        String normalized = question == null ? "" : question.strip().toLowerCase(Locale.ROOT);
        List<Candidate> candidates = selectCandidates(normalized);
        List<TimeIntentResult.TimeMention> selectedMentions = candidates.stream()
                .map(candidate -> mention(normalized, candidate.start(), candidate.end()))
                .toList();
        if (candidates.isEmpty()) {
            return new TimeIntentResult(TimeIntentResult.Status.NONE, List.of(), null, null, "");
        }
        // 截断防护：JioNLP 式内核会把“近一年半”静默当成“近一年”。凡滚动时长
        // 后紧跟“半”，说明是复合量词，必须拒绝而不是悄悄截断。
        for (Candidate candidate : candidates) {
            if (candidate.kind() == ExpressionKind.DURATION
                    && candidate.end() < normalized.length()
                    && normalized.charAt(candidate.end()) == '半') {
                return new TimeIntentResult(TimeIntentResult.Status.UNSUPPORTED,
                        selectedMentions, null, null, "复合时长表达（含“半”）暂不支持，请改用具体天数或日期");
            }
        }
        try {
            List<Parsed> parsedCandidates = candidates.stream()
                    .map(candidate -> parse(candidate, now))
                    .toList();
            if (parsedCandidates.stream().anyMatch(parsed -> parsed == null)) {
                boolean crossYearMonthDay = false;
                for (int i = 0; i < candidates.size(); i++) {
                    if (parsedCandidates.get(i) == null
                            && candidates.get(i).kind() == ExpressionKind.MONTH_DAY_RANGE) {
                        crossYearMonthDay = true;
                    }
                }
                String reason = crossYearMonthDay
                        ? "暂不支持跨年的日期区间（例如12月30日到1月2日），请改用带年份的具体日期"
                        : "时间表达式暂不支持";
                return new TimeIntentResult(TimeIntentResult.Status.UNSUPPORTED,
                        selectedMentions, null, null, reason);
            }
            Parsed parsed = parsedCandidates.get(0);
            boolean oneSharedRange = parsedCandidates.stream()
                    .allMatch(candidate -> candidate.range().equals(parsed.range()));
            if (parsedCandidates.size() > 1 && !oneSharedRange) {
                return new TimeIntentResult(TimeIntentResult.Status.MULTIPLE, selectedMentions,
                        null, null, "原始问题包含多个时间范围");
            }
            TimeIntentResult.Status status = parsed.range().from().equals(parsed.range().to())
                    ? TimeIntentResult.Status.EMPTY
                    : TimeIntentResult.Status.PARSED;
            return new TimeIntentResult(status, selectedMentions,
                    parsed.intent(), parsed.range(), "");
        } catch (DateTimeException | NumberFormatException | ArithmeticException invalidExpression) {
            return new TimeIntentResult(TimeIntentResult.Status.UNSUPPORTED,
                    selectedMentions, null, null, "时间表达式无效");
        }
    }

    private static List<Candidate> selectCandidates(String question) {
        List<Candidate> all = new ArrayList<>();
        addMatches(question, CALENDAR_DATE_RANGE, ExpressionKind.CALENDAR_DATE_RANGE, all);
        addMatches(question, CHINESE_CALENDAR_DATE_RANGE, ExpressionKind.CHINESE_CALENDAR_DATE_RANGE, all);
        addMatches(question, MONTH_DAY_RANGE, ExpressionKind.MONTH_DAY_RANGE, all);
        addMatches(question, HOUR_RANGE, ExpressionKind.HOUR_RANGE, all);
        addMatches(question, FULL_NUMERIC_DATE, ExpressionKind.FULL_NUMERIC_DATE, all);
        addMatches(question, YEAR_HALF, ExpressionKind.YEAR_HALF, all);
        addMatches(question, DAY_PART, ExpressionKind.DAY_PART, all);
        addMatches(question, QUALIFIED_MONTH_DAY, ExpressionKind.QUALIFIED_MONTH_DAY, all);
        addMatches(question, QUALIFIED_WEEK, ExpressionKind.QUALIFIED_WEEK, all);
        addMatches(question, WEEK_RANGE, ExpressionKind.WEEK_RANGE, all);
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

    private static Parsed parse(Candidate candidate, Instant now) {
        String expression = candidate.text();
        return switch (candidate.kind()) {
            case CALENDAR_DATE_RANGE -> parseCalendarDateRange(expression);
            case CHINESE_CALENDAR_DATE_RANGE -> parseChineseCalendarDateRange(expression);
            case MONTH_DAY_RANGE -> parseMonthDayRange(expression, now);
            case HOUR_RANGE -> parseHourRange(expression, now);
            case FULL_NUMERIC_DATE -> parseFullDate(expression);
            case YEAR_HALF -> parseYearHalf(expression, now);
            case DAY_PART -> parseDayPart(expression, now);
            case QUALIFIED_MONTH_DAY -> parseQualifiedMonthDay(expression, now);
            case QUALIFIED_WEEK -> parseQualifiedWeek(expression, now);
            case WEEK_RANGE -> parseWeekRange(expression, now);
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
        return dateRange(expression, LocalDate.parse(matcher.group(1)), LocalDate.parse(matcher.group(2)));
    }

    private static Parsed parseChineseCalendarDateRange(String expression) {
        Matcher matcher = CHINESE_CALENDAR_DATE_RANGE.matcher(expression);
        matcher.matches();
        return dateRange(expression, LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))),
                LocalDate.of(Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)),
                        Integer.parseInt(matcher.group(6))));
    }

    /** 同年内的“8月1日到8月3日”式组合：一个连续区间，不是两个独立日期。 */
    private static Parsed parseMonthDayRange(String expression, Instant now) {
        Matcher matcher = MONTH_DAY_RANGE.matcher(expression);
        matcher.matches();
        int year = now.atZone(PARK_ZONE).getYear();
        LocalDate from = LocalDate.of(year, Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)));
        LocalDate to = LocalDate.of(year, Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(4)));
        return dateRange(expression, from, to);
    }

    /** 同日内“9点到12点”式小时区间。 */
    private static Parsed parseHourRange(String expression, Instant now) {
        Matcher matcher = HOUR_RANGE.matcher(expression);
        matcher.matches();
        LocalDate day = now.atZone(PARK_ZONE).toLocalDate();
        ZoneId zone = PARK_ZONE;
        int fromHour = Integer.parseInt(matcher.group(1));
        int fromMinute = minutes(matcher.group(2));
        int toHour = Integer.parseInt(matcher.group(3));
        int toMinute = minutes(matcher.group(4));
        Instant from = day.atTime(fromHour, fromMinute).atZone(zone).toInstant();
        Instant to = day.atTime(toHour, toMinute).atZone(zone).toInstant();
        if (!to.isAfter(from)) {
            return null;
        }
        TimeIntent intent = new TimeIntent(expression, TimeIntent.Kind.DAY_PART, 0, null,
                day, null, TimeIntent.DayPart.MORNING);
        return new Parsed(intent, new QueryPlan.TimeRange(from, to));
    }

    private static int minutes(String minuteGroup) {
        if (minuteGroup == null) {
            return 0;
        }
        return Integer.parseInt(minuteGroup.replace("分", ""));
    }

    private static Parsed parseFullDate(String expression) {
        Matcher matcher = FULL_NUMERIC_DATE.matcher(expression);
        matcher.matches();
        return singleDate(expression, LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
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

    /** 时段为固定的本地日历窗口，不随参考时刻截断。 */
    private static Parsed parseDayPart(String expression, Instant now) {
        Matcher matcher = DAY_PART.matcher(expression);
        matcher.matches();
        LocalDate day = now.atZone(PARK_ZONE).toLocalDate();
        TimeIntent.DayPart dayPart = switch (matcher.group(2)) {
            case "上午" -> TimeIntent.DayPart.MORNING;
            case "下午" -> TimeIntent.DayPart.AFTERNOON;
            default -> TimeIntent.DayPart.EVENING;
        };
        Instant from = atStartOfDay(day).plusSeconds(dayPartStartHour(dayPart) * 3600L);
        Instant to = atStartOfDay(day).plusSeconds(dayPartEndHour(dayPart) * 3600L);
        return new Parsed(new TimeIntent(expression, TimeIntent.Kind.DAY_PART, 0, null, day, null, dayPart),
                new QueryPlan.TimeRange(from, to));
    }

    private static int dayPartStartHour(TimeIntent.DayPart part) {
        return switch (part) {
            case MORNING -> 7;
            case AFTERNOON -> 12;
            case EVENING -> 18;
        };
    }

    private static int dayPartEndHour(TimeIntent.DayPart part) {
        return switch (part) {
            case MORNING -> 12;
            case AFTERNOON -> 18;
            case EVENING -> 24;
        };
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

    /**
     * “上周一到周三”式组合周区间：两端均为星期词，可各自带本周/上周/下周限定词；
     * 后一端缺限定词时继承前一端（与 Python sidecar 的组合逻辑一致）。换算全部由
     * 确定性日历完成，不依赖模型算术。起点晚于终点（如“本周三到本周一”）视为非法表达式。
     */
    private static Parsed parseWeekRange(String expression, Instant now) {
        Matcher matcher = WEEK_RANGE.matcher(expression);
        matcher.matches();
        LocalDate today = now.atZone(PARK_ZONE).toLocalDate();
        LocalDate currentWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int q1 = weekQualifierWeeks(matcher.group(1));
        int q2 = matcher.group(3) != null ? weekQualifierWeeks(matcher.group(3)) : q1;
        LocalDate from = currentWeekStart.plusWeeks(q1).plusDays(weekdayOffset(matcher.group(2)));
        LocalDate to = currentWeekStart.plusWeeks(q2).plusDays(weekdayOffset(matcher.group(4)));
        return dateRange(expression, from, to);
    }

    private static int weekQualifierWeeks(String qualifier) {
        if (qualifier == null) {
            return 0;
        }
        return switch (qualifier) {
            case "上周" -> -1;
            case "本周" -> 0;
            case "下周" -> 1;
            default -> 0;
        };
    }

    private static int weekdayOffset(String weekday) {
        return switch (weekday) {
            case "一" -> 0;
            case "二" -> 1;
            case "三" -> 2;
            case "四" -> 3;
            case "五" -> 4;
            case "六" -> 5;
            case "日", "天", "末" -> 6;
            default -> throw new IllegalArgumentException("invalid weekday: " + weekday);
        };
    }

    private static Parsed parseDuration(String expression, Instant now) {
        Matcher matcher = DURATION.matcher(expression);
        matcher.matches();
        long count = parseNumber(matcher.group(1));
        String unit = matcher.group(2);
        if (count <= 0) {
            return null;
        }
        QueryPlan.TimeRange range;
        TimeIntent.Unit intentUnit;
        switch (unit) {
            case "小时", "个小时" -> {
                range = new QueryPlan.TimeRange(
                        now.minusSeconds(Math.multiplyExact(count, 3_600)), now);
                intentUnit = TimeIntent.Unit.HOUR;
            }
            case "天", "日" -> {
                range = new QueryPlan.TimeRange(
                        now.minusSeconds(Math.multiplyExact(count, 86_400)), now);
                intentUnit = TimeIntent.Unit.DAY;
            }
            case "周", "星期" -> {
                range = new QueryPlan.TimeRange(
                        now.minusSeconds(Math.multiplyExact(count, 7 * 86_400)), now);
                intentUnit = TimeIntent.Unit.WEEK;
            }
            case "月", "个月" -> {
                range = new QueryPlan.TimeRange(now.atZone(PARK_ZONE).minusMonths(count).toInstant(), now);
                intentUnit = TimeIntent.Unit.MONTH;
            }
            case "季度", "个季度" -> {
                range = new QueryPlan.TimeRange(
                        now.atZone(PARK_ZONE).minusMonths(Math.multiplyExact(count, 3)).toInstant(), now);
                intentUnit = TimeIntent.Unit.QUARTER;
            }
            case "年", "个年" -> {
                range = new QueryPlan.TimeRange(now.atZone(PARK_ZONE).minusYears(count).toInstant(), now);
                intentUnit = TimeIntent.Unit.YEAR;
            }
            default -> {
                return null;
            }
        }
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
            case "今天", "今日" -> cappedPeriod(expression,
                    TimeIntent.Kind.CALENDAR_PERIOD, today, today,
                    new QueryPlan.TimeRange(atStartOfDay(today), now), now);
            case "昨天", "昨日" -> calendarPeriod(expression, today.minusDays(1), today);
            case "前天" -> calendarPeriod(expression, today.minusDays(2), today.minusDays(1));
            case "明天" -> calendarPeriod(expression, today.plusDays(1), today.plusDays(2));
            case "后天" -> calendarPeriod(expression, today.plusDays(2), today.plusDays(3));
            case "本周" -> cappedPeriod(expression,
                    TimeIntent.Kind.CALENDAR_PERIOD, weekStart, today,
                    new QueryPlan.TimeRange(atStartOfDay(weekStart), now), now);
            case "上周" -> calendarPeriod(expression, weekStart.minusWeeks(1), weekStart);
            case "下周" -> calendarPeriod(expression, weekStart.plusWeeks(1), weekStart.plusWeeks(2));
            case "本月", "这个月" -> cappedPeriod(expression,
                    TimeIntent.Kind.CALENDAR_PERIOD, monthStart, today,
                    new QueryPlan.TimeRange(atStartOfDay(monthStart), now), now);
            case "上月" -> calendarPeriod(expression, monthStart.minusMonths(1), monthStart);
            case "下月", "下个月" -> calendarPeriod(expression, monthStart.plusMonths(1), monthStart.plusMonths(2));
            case "本季度", "这个季度" -> cappedPeriod(expression,
                    TimeIntent.Kind.CALENDAR_PERIOD, quarterStart, today,
                    new QueryPlan.TimeRange(atStartOfDay(quarterStart), now), now);
            case "上季度" -> calendarPeriod(expression, quarterStart.minusMonths(3), quarterStart);
            case "下季度" -> calendarPeriod(expression, quarterStart.plusMonths(3), quarterStart.plusMonths(6));
            case "今年", "本年" -> cappedPeriod(expression,
                    TimeIntent.Kind.CALENDAR_PERIOD, LocalDate.of(today.getYear(), 1, 1), today,
                    new QueryPlan.TimeRange(atStartOfDay(LocalDate.of(today.getYear(), 1, 1)), now), now);
            case "去年" -> calendarPeriod(expression, LocalDate.of(today.getYear() - 1, 1, 1),
                    LocalDate.of(today.getYear(), 1, 1));
            case "上半年" -> calendarPeriod(expression, LocalDate.of(today.getYear(), 1, 1),
                    LocalDate.of(today.getYear(), 7, 1));
            case "下半年" -> calendarPeriod(expression, LocalDate.of(today.getYear(), 7, 1),
                    LocalDate.of(today.getYear() + 1, 1, 1));
            default -> null;
        };
    }

    /**
     * 进行中的周期：数据窗口为 [周期开始, 参考时刻)。参考时刻恰好落在周期
     * 开始零点时窗口为零宽 —— 返回显式 EMPTY，不伪造非空范围。
     */
    private static Parsed cappedPeriod(String expression, TimeIntent.Kind kind,
                                       LocalDate fromDate, LocalDate toDate,
                                       QueryPlan.TimeRange uncapped, Instant now) {
        if (!now.isAfter(uncapped.from())) {
            TimeIntent intent = new TimeIntent(expression, kind, 0, null, fromDate, fromDate, null);
            return new Parsed(intent, new QueryPlan.TimeRange(now, now));
        }
        TimeIntent intent = new TimeIntent(expression, kind, 0, null, fromDate, toDate, null);
        return new Parsed(intent, uncapped);
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

    private static TimeIntentResult.TimeMention mention(String question, int start, int end) {
        return new TimeIntentResult.TimeMention(question.substring(start, end), start, end);
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
        CALENDAR_DATE_RANGE, CHINESE_CALENDAR_DATE_RANGE, MONTH_DAY_RANGE, HOUR_RANGE,
        FULL_NUMERIC_DATE, YEAR_HALF, DAY_PART, QUALIFIED_MONTH_DAY, QUALIFIED_WEEK,
        WEEK_RANGE, DURATION, YEAR_MONTH, YEAR_ONLY, MONTH_DAY, MONTH_ONLY, BASE_PERIOD
    }
}
