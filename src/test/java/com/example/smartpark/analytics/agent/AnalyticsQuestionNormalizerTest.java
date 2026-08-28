package com.example.smartpark.analytics.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsQuestionNormalizerTest {

    private final AnalyticsQuestionNormalizer normalizer = new AnalyticsQuestionNormalizer();

    @Test
    void ignoresModelInventedDateGroupingForRollingEnergyQuestion() {
        var modelUnderstanding = new AnalyticsModelClient.QuestionUnderstanding(
                "过去5天各楼宇能耗",
                List.of("energy_kwh"),
                List.of(),
                null,
                List.of("stat_date", "building"),
                Map.of("building_id", "B99"));

        var normalized = normalizer.normalize("过去5天各楼宇能耗", modelUnderstanding);

        assertThat(normalized.requestedDimensions()).containsExactly("building_id");
        assertThat(normalized.requestedFilters()).isEmpty();
    }

    @Test
    void preservesOnlyExplicitEntityFiltersAndCanonicalDimensionNames() {
        var modelUnderstanding = new AnalyticsModelClient.QuestionUnderstanding(
                "B1楼宇按小时能耗",
                List.of("energy_kwh"),
                List.of(),
                null,
                List.of("building_id", "hour", "snapshot_at"),
                Map.of("building_id", "B1", "meter_id", "MTR-9"));

        var normalized = normalizer.normalize("B1楼宇按小时能耗", modelUnderstanding);

        assertThat(normalized.requestedDimensions()).containsExactly("hour_ts");
        assertThat(normalized.requestedFilters()).containsExactlyEntriesOf(Map.of("building_id", "B1"));
    }

    @Test
    void preservesVisualizationDimensionsOnlyWhenTheQuestionStatesTheirMeaning() {
        var modelUnderstanding = new AnalyticsModelClient.QuestionUnderstanding(
                "过去5天楼宇空间分布",
                List.of("energy_kwh"),
                List.of(),
                null,
                List.of("building_name", "map_x", "map_y", "stat_date", "hour_of_day"),
                Map.of());

        var normalized = normalizer.normalize("过去5天楼宇空间分布", modelUnderstanding);

        assertThat(normalized.requestedDimensions())
                .containsExactly("building_name", "map_x", "map_y")
                .doesNotContain("stat_date", "hour_of_day");
    }

    @Test
    void acceptsCalendarAndHeatmapDimensionAliases() {
        var modelUnderstanding = new AnalyticsModelClient.QuestionUnderstanding(
                "过去5天按日期热力图",
                List.of("energy_kwh"),
                List.of(),
                null,
                List.of("date", "hour_of_day"),
                Map.of());

        var normalized = normalizer.normalize("过去5天按日期热力图", modelUnderstanding);

        assertThat(normalized.requestedDimensions()).containsExactly("stat_date");
    }
}
