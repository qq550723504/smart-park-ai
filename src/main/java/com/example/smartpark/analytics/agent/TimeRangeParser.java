package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.QuestionTokenScanner;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TimeRangeParser {

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
    private static final Pattern MONTH_DAY = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{1,2})月(\\d{1,2})日(?![A-Za-z0-9_-])");
    private static final Pattern QUALIFIED_MONTH_DAY = Pattern.compile("(本月|上月)(\\d{1,2})日");
    private static final Pattern DURATION = Pattern.compile(
            "(?:过去|最近|近)([0-9]+|[一二两三四五六七八九十百千万]+)"
                    + "(小时|个?月|个?季度|周|星期|天|日)");
    private static final Pattern YEAR_MONTH = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{4})年(\\d{1,2})月(?![A-Za-z0-9_-])");
    private static final Pattern YEAR_ONLY = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{4})年(?![A-Za-z0-9_-])");
    private static final Pattern MONTH_ONLY = Pattern.compile(
            "(?<![A-Za-z0-9_-])(\\d{1,2})月(?![A-Za-z0-9_-])");
    private static final Pattern QUALIFIED_WEEK = Pattern.compile("(本周|上周)([一二三四五六日天末])");
    private static final Pattern TIME_EXPRESSION = Pattern.compile(
            "(?:" + CHINESE_CALENDAR_DATE_RANGE.pattern() + "|"
                    + CALENDAR_DATE_RANGE.pattern() + "|"
                    + FULL_NUMERIC_DATE.pattern() + "|"
                    + QUALIFIED_WEEK.pattern() + "|"
                    + QUALIFIED_MONTH_DAY.pattern() + "|"
                    + MONTH_DAY.pattern() + "|"
                    + DURATION.pattern() + "|"
                    + YEAR_MONTH.pattern() + "|"
                    + YEAR_ONLY.pattern() + "|"
                    + MONTH_ONLY.pattern() + "|"
                    + "上上周|上上月|下周|下月|本季度|上季度|下季度|季度|今年|去年|本年|上半年|下半年|"
                    + "明天|后天|未来|今天|今日|昨天|昨日|前天|本周|上周[一二三四五六日天末]?|本月|上月"
                    + ")");

    enum Status { NONE, PARSED, UNSUPPORTED, MULTIPLE }

    record ParseResult(Status status, QueryPlan.TimeRange timeRange, String expression) {
    }

    ParseResult parse(String question, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }
        String normalized = question == null ? "" : question.strip().toLowerCase(Locale.ROOT);
        List<String> expressions = expressionsIn(normalized);
        if (expressions.isEmpty()) {
            return new ParseResult(Status.NONE, null, "");
        }
        if (expressions.size() > 1) {
            return new ParseResult(Status.MULTIPLE, null, String.join("、", expressions));
        }
        String expression = expressions.get(0);
        try {
            QueryPlan.TimeRange range = parseExpression(expression, now);
            return range == null
                    ? new ParseResult(Status.UNSUPPORTED, null, expression)
                    : new ParseResult(Status.PARSED, range, expression);
        } catch (DateTimeException | NumberFormatException | ArithmeticException invalidExpression) {
            return new ParseResult(Status.UNSUPPORTED, null, expression);
        }
    }

    private static List<String> expressionsIn(String question) {
        List<String> expressions = new ArrayList<>();
        Matcher matcher = TIME_EXPRESSION.matcher(question);
        while (matcher.find()) {
            if (isDateExpression(matcher.group())
                    && !QuestionTokenScanner.isStandaloneSpan(question, matcher.start(), matcher.end())) {
                continue;
            }
            expressions.add(matcher.group());
        }
        return expressions;
    }

    private static boolean isDateExpression(String expression) {
        return CALENDAR_DATE_RANGE.matcher(expression).matches()
                || CHINESE_CALENDAR_DATE_RANGE.matcher(expression).matches()
                || FULL_NUMERIC_DATE.matcher(expression).matches()
                || MONTH_DAY.matcher(expression).matches()
                || YEAR_MONTH.matcher(expression).matches()
                || YEAR_ONLY.matcher(expression).matches()
                || MONTH_ONLY.matcher(expression).matches();
    }

    private static QueryPlan.TimeRange parseExpression(String expression, Instant now) {
        Matcher calendarRange = CALENDAR_DATE_RANGE.matcher(expression);
        if (calendarRange.matches()) {
            LocalDate from = LocalDate.parse(calendarRange.group(1));
            LocalDate to = LocalDate.parse(calendarRange.group(2));
            return orderedDateRange(from, to);
        }
        Matcher chineseCalendarRange = CHINESE_CALENDAR_DATE_RANGE.matcher(expression);
        if (chineseCalendarRange.matches()) {
            LocalDate from = LocalDate.of(
                    Integer.parseInt(chineseCalendarRange.group(1)),
                    Integer.parseInt(chineseCalendarRange.group(2)),
                    Integer.parseInt(chineseCalendarRange.group(3)));
            LocalDate to = LocalDate.of(
                    Integer.parseInt(chineseCalendarRange.group(4)),
                    Integer.parseInt(chineseCalendarRange.group(5)),
                    Integer.parseInt(chineseCalendarRange.group(6)));
            return orderedDateRange(from, to);
        }
        Matcher fullDate = FULL_NUMERIC_DATE.matcher(expression);
        if (fullDate.matches()) {
            return singleDateRange(LocalDate.of(
                    Integer.parseInt(fullDate.group(1)),
                    Integer.parseInt(fullDate.group(2)),
                    Integer.parseInt(fullDate.group(3))));
        }
        Matcher monthDay = MONTH_DAY.matcher(expression);
        if (monthDay.matches()) {
            LocalDate today = now.atZone(PARK_ZONE).toLocalDate();
            return singleDateRange(LocalDate.of(today.getYear(),
                    Integer.parseInt(monthDay.group(1)), Integer.parseInt(monthDay.group(2))));
        }
        Matcher qualifiedMonthDay = QUALIFIED_MONTH_DAY.matcher(expression);
        if (qualifiedMonthDay.matches()) {
            LocalDate currentMonth = now.atZone(PARK_ZONE).toLocalDate().withDayOfMonth(1);
            LocalDate month = "上月".equals(qualifiedMonthDay.group(1))
                    ? currentMonth.minusMonths(1) : currentMonth;
            return singleDateRange(month.withDayOfMonth(Integer.parseInt(qualifiedMonthDay.group(2))));
        }
        Matcher duration = DURATION.matcher(expression);
        if (duration.matches()) {
            long count = parseNumber(duration.group(1));
            return relativeRange(now, count, duration.group(2));
        }
        Matcher yearMonth = YEAR_MONTH.matcher(expression);
        if (yearMonth.matches()) {
            LocalDate from = LocalDate.of(Integer.parseInt(yearMonth.group(1)),
                    Integer.parseInt(yearMonth.group(2)), 1);
            return localDateRange(from, from.plusMonths(1));
        }
        Matcher yearOnly = YEAR_ONLY.matcher(expression);
        if (yearOnly.matches()) {
            LocalDate from = LocalDate.of(Integer.parseInt(yearOnly.group(1)), 1, 1);
            return localDateRange(from, from.plusYears(1));
        }
        Matcher monthOnly = MONTH_ONLY.matcher(expression);
        if (monthOnly.matches()) {
            int year = now.atZone(PARK_ZONE).getYear();
            LocalDate from = LocalDate.of(year, Integer.parseInt(monthOnly.group(1)), 1);
            return localDateRange(from, from.plusMonths(1));
        }
        return fixedRange(expression, now);
    }

    private static QueryPlan.TimeRange relativeRange(Instant now, long count, String unit) {
        if (count <= 0) {
            return null;
        }
        return switch (unit) {
            case "小时" -> new QueryPlan.TimeRange(now.minusSeconds(Math.multiplyExact(count, 3_600)), now);
            case "天", "日" -> new QueryPlan.TimeRange(now.minusSeconds(Math.multiplyExact(count, 86_400)), now);
            case "周", "星期" -> new QueryPlan.TimeRange(now.minusSeconds(Math.multiplyExact(count, 7 * 86_400)), now);
            case "月", "个月" -> new QueryPlan.TimeRange(now.atZone(PARK_ZONE).minusMonths(count).toInstant(), now);
            case "季度", "个季度" -> new QueryPlan.TimeRange(now.atZone(PARK_ZONE)
                    .minusMonths(Math.multiplyExact(count, 3)).toInstant(), now);
            default -> null;
        };
    }

    private static QueryPlan.TimeRange fixedRange(String expression, Instant now) {
        LocalDate today = now.atZone(PARK_ZONE).toLocalDate();
        LocalDate currentWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate currentMonthStart = today.withDayOfMonth(1);
        int quarterStartMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
        LocalDate currentQuarterStart = LocalDate.of(today.getYear(), quarterStartMonth, 1);
        return switch (expression) {
            case "今天", "今日" -> new QueryPlan.TimeRange(atStartOfDay(today), now);
            case "昨天", "昨日" -> localDateRange(today.minusDays(1), today);
            case "前天" -> localDateRange(today.minusDays(2), today.minusDays(1));
            case "明天" -> localDateRange(today.plusDays(1), today.plusDays(2));
            case "后天" -> localDateRange(today.plusDays(2), today.plusDays(3));
            case "本周" -> new QueryPlan.TimeRange(atStartOfDay(currentWeekStart), now);
            case "上周" -> localDateRange(currentWeekStart.minusWeeks(1), currentWeekStart);
            case "下周" -> localDateRange(currentWeekStart.plusWeeks(1), currentWeekStart.plusWeeks(2));
            case "本月" -> new QueryPlan.TimeRange(atStartOfDay(currentMonthStart), now);
            case "上月" -> localDateRange(currentMonthStart.minusMonths(1), currentMonthStart);
            case "下月" -> localDateRange(currentMonthStart.plusMonths(1), currentMonthStart.plusMonths(2));
            case "本季度" -> new QueryPlan.TimeRange(atStartOfDay(currentQuarterStart), now);
            case "上季度" -> localDateRange(currentQuarterStart.minusMonths(3), currentQuarterStart);
            case "下季度" -> localDateRange(currentQuarterStart.plusMonths(3), currentQuarterStart.plusMonths(6));
            case "今年", "本年" -> new QueryPlan.TimeRange(atStartOfDay(LocalDate.of(today.getYear(), 1, 1)), now);
            case "去年" -> localDateRange(LocalDate.of(today.getYear() - 1, 1, 1),
                    LocalDate.of(today.getYear(), 1, 1));
            case "上半年" -> localDateRange(LocalDate.of(today.getYear(), 1, 1),
                    LocalDate.of(today.getYear(), 7, 1));
            case "下半年" -> localDateRange(LocalDate.of(today.getYear(), 7, 1),
                    LocalDate.of(today.getYear() + 1, 1, 1));
            default -> qualifiedWeek(expression, currentWeekStart);
        };
    }

    private static QueryPlan.TimeRange qualifiedWeek(String expression, LocalDate currentWeekStart) {
        Matcher matcher = QUALIFIED_WEEK.matcher(expression);
        if (!matcher.matches()) {
            return null;
        }
        LocalDate weekStart = "上周".equals(matcher.group(1))
                ? currentWeekStart.minusWeeks(1) : currentWeekStart;
        String qualifier = matcher.group(1);
        String dayQualifier = matcher.group(2);
        if ("末".equals(dayQualifier)) {
            return localDateRange(weekStart.plusDays(5), weekStart.plusDays(7));
        }
        int dayOffset = switch (dayQualifier) {
            case "一" -> 0;
            case "二" -> 1;
            case "三" -> 2;
            case "四" -> 3;
            case "五" -> 4;
            case "六" -> 5;
            case "日", "天" -> 6;
            default -> throw new IllegalArgumentException("无法识别上周的日期限定: " + qualifier);
        };
        LocalDate day = weekStart.plusDays(dayOffset);
        return localDateRange(day, day.plusDays(1));
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

    private static QueryPlan.TimeRange orderedDateRange(LocalDate from, LocalDate toInclusive) {
        if (toInclusive.isBefore(from)) {
            return null;
        }
        return localDateRange(from, toInclusive.plusDays(1));
    }

    private static QueryPlan.TimeRange singleDateRange(LocalDate date) {
        return localDateRange(date, date.plusDays(1));
    }

    private static QueryPlan.TimeRange localDateRange(LocalDate fromInclusive, LocalDate toExclusive) {
        return new QueryPlan.TimeRange(atStartOfDay(fromInclusive), atStartOfDay(toExclusive));
    }

    private static Instant atStartOfDay(LocalDate date) {
        return date.atStartOfDay(PARK_ZONE).toInstant();
    }
}
