package com.example.smartpark.analytics.agent.time;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnicodeOffsetMapperTest {

    @Test
    void convertsCodePointSpanToUtf16WhenQuestionContainsEmoji() {
        String question = "🔔今天上午能耗";

        assertThat(UnicodeOffsetMapper.toUtf16(question, 1, 7))
                .isEqualTo(new UnicodeOffsetMapper.Span(2, 8));
    }

    @Test
    void convertsUtf16SpanToCodePointsWhenMentionContainsSupplementaryCharacter() {
        String question = "2026😀年能耗";

        assertThat(UnicodeOffsetMapper.toCodePoints(question, 4, 7))
                .isEqualTo(new UnicodeOffsetMapper.Span(4, 6));
    }

    @Test
    void rejectsOffsetsThatSplitSurrogatePairOrExceedQuestion() {
        String question = "🔔今天";

        assertThatThrownBy(() -> UnicodeOffsetMapper.toCodePoints(question, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UnicodeOffsetMapper.toUtf16(question, 0, 99))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UnicodeOffsetMapper.toCodePoints(question, 1, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
