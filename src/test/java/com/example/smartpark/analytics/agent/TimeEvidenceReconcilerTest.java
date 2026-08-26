package com.example.smartpark.analytics.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeEvidenceReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-08-24T16:00:00Z");
    private final TimeEvidenceReconciler reconciler = new TimeEvidenceReconciler();

    private static TimeIntentResult parsed(String mention, String question) {
        TimeIntentResult full = new WhitelistTimeIntentProvider().resolve(question, NOW);
        return new TimeIntentResult(TimeIntentResult.Status.PARSED,
                full.mentions(), full.intent(), full.timeRange(), "");
    }

    @Test
    void parserParsedPlusModelEmptyStaysParsed() {
        var parser = parsed("x", "过去一周能耗");
        var result = reconciler.reconcile(parser, List.of(), "过去一周能耗");

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
    }

    @Test
    void parserParsedPlusMatchingModelMentionsStayParsed() {
        var parser = parsed("x", "过去一周能耗");
        var result = reconciler.reconcile(parser, List.of("过去一周"), "过去一周能耗");

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
    }

    @Test
    void modelMentionsWithoutParserResolutionFailClosedAsUnsupported() {
        // “上个礼拜”不在白名单内：模型发现了它，解析器无法解析 → UNSUPPORTED，
        // 而不是静默使用默认 lookback。
        var parser = new WhitelistTimeIntentProvider().resolve("上个礼拜能耗", NOW);

        assertThat(parser.status()).isEqualTo(TimeIntentResult.Status.NONE);

        var result = reconciler.reconcile(parser, List.of("上个礼拜"), "上个礼拜能耗");

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.UNSUPPORTED);
    }

    @Test
    void nonVerbatimModelMentionIsRejected() {
        var parser = parsed("x", "过去一周能耗");

        var result = reconciler.reconcile(parser, List.of("过去两周"), "过去一周能耗");

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.UNSUPPORTED);
    }

    @Test
    void modelMentionStraddlingParserMentionsIsAmbiguous() {
        // 模型片段横跨两个解析器 mention（“本月”和“去年”），无法归入任何
        // 单一可解析表达 → AMBIGUOUS。
        var question = "对比本月和去年能耗";
        var parser = new WhitelistTimeIntentProvider().resolve(question, NOW);

        assertThat(parser.status()).isEqualTo(TimeIntentResult.Status.MULTIPLE);

        var result = reconciler.reconcile(parser, List.of("月和去年"), question);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.AMBIGUOUS);
    }

    @Test
    void nestedVerbatimModelFragmentInsideParserMentionIsAccepted() {
        // 模型在完整表达之外额外返回嵌套片段（“2026年8月25日”中的“8月25日”）
        // 属于合理的识别结果：只要落在单一解析器 mention 内部就视为一致，
        // 不应把合法问题误杀为 AMBIGUOUS。
        var question = "2026年8月25日能耗";
        var parser = new WhitelistTimeIntentProvider().resolve(question, NOW);

        assertThat(parser.status()).isEqualTo(TimeIntentResult.Status.PARSED);

        var result = reconciler.reconcile(parser,
                List.of("2026年8月25日", "8月25日"), question);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
    }

    @Test
    void multipleDistinctRangesRemainRejectedEvenWithModelAgreement() {
        var question = "对比本月和去年能耗";
        var parser = new WhitelistTimeIntentProvider().resolve(question, NOW);

        assertThat(parser.status()).isEqualTo(TimeIntentResult.Status.MULTIPLE);

        var result = reconciler.reconcile(parser, List.of("本月", "去年"), question);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.MULTIPLE);
    }

    @Test
    void emptyPeriodWithModelMentionReturnsEmpty() {
        var question = "今天能耗";
        var parser = new WhitelistTimeIntentProvider().resolve(question, NOW);

        assertThat(parser.status()).isEqualTo(TimeIntentResult.Status.EMPTY);

        var result = reconciler.reconcile(parser, List.of("今天"), question);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.EMPTY);
    }

    @Test
    void bothEvidenceSourcesEmptyYieldNone() {
        var question = "查一下总能耗";
        var parser = new WhitelistTimeIntentProvider().resolve(question, NOW);

        assertThat(parser.status()).isEqualTo(TimeIntentResult.Status.NONE);

        var result = reconciler.reconcile(parser, List.of(), question);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.NONE);
    }

    @Test
    void blankModelMentionFailsClosedInsteadOfPassingThrough() {
        var parser = parsed("x", "过去一周能耗");

        // 空白 mention 与原文缺失的 mention 都以 UNSUPPORTED 收口，绝不放行。
        var blankResult = reconciler.reconcile(parser, List.of("  "), "过去一周能耗");
        assertThat(blankResult.status()).isEqualTo(TimeIntentResult.Status.UNSUPPORTED);

        var missingResult = reconciler.reconcile(parser, List.of("不存在的时间词"), "过去一周能耗");
        assertThat(missingResult.status()).isEqualTo(TimeIntentResult.Status.UNSUPPORTED);
    }
}
