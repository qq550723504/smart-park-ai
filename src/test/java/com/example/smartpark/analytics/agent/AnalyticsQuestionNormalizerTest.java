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
    void derivesExplicitBuildingScopeWhenTheModelOmitsFilters() {
        var modelUnderstanding = new AnalyticsModelClient.QuestionUnderstanding(
                "B1楼宇的能耗",
                List.of("energy_kwh"),
                List.of(),
                null,
                List.of(),
                Map.of());

        var normalized = normalizer.normalize("building_id=B1 楼宇的能耗", modelUnderstanding);

        assertThat(normalized.requestedFilters()).containsExactlyEntriesOf(Map.of("building_id", "B1"));
    }

    @Test
    void canonicalizesBuildingFiltersBeforeTheyReachCaseSensitiveBindings() {
        var modelUnderstanding = new AnalyticsModelClient.QuestionUnderstanding(
                "b1楼宇的能耗",
                List.of("energy_kwh"),
                List.of(),
                null,
                List.of(),
                Map.of("building_id", "b1"));

        var explicit = normalizer.normalize("building_id=b1 楼宇的能耗", modelUnderstanding);
        var inferred = normalizer.normalize("b1楼宇的能耗", modelUnderstanding);

        assertThat(explicit.requestedFilters()).containsExactlyEntriesOf(Map.of("building_id", "B1"));
        assertThat(inferred.requestedFilters()).containsExactlyEntriesOf(Map.of("building_id", "B1"));
    }

    @Test
    void stopsExplicitBuildingScopeBeforeSentencePunctuation() {
        var modelUnderstanding = new AnalyticsModelClient.QuestionUnderstanding(
                "楼宇能耗", List.of("energy_kwh"), List.of());

        var sentence = normalizer.normalize("building_id=B1. 分析该楼宇能耗", modelUnderstanding);
        var clause = normalizer.normalize("building_id=b2: 分析该楼宇能耗", modelUnderstanding);

        assertThat(sentence.requestedFilters()).containsExactlyEntriesOf(Map.of("building_id", "B1"));
        assertThat(clause.requestedFilters()).containsExactlyEntriesOf(Map.of("building_id", "B2"));
    }

    @Test
    void rejectsConflictingExplicitBuildingScopes() {
        var modelUnderstanding = new AnalyticsModelClient.QuestionUnderstanding(
                "楼宇能耗", List.of("energy_kwh"), List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> normalizer.normalize(
                        "building_id=B1 与 building_id=B2 的能耗", modelUnderstanding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多个 building_id");
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

    @Test
    void recognizesEachParkingZoneAsAnExplicitGrouping() {
        var modelUnderstanding = new AnalyticsModelClient.QuestionUnderstanding(
                "过去5天各停车区域进场量",
                List.of("停车进场量"),
                List.of(),
                null,
                List.of("parking_zone"),
                Map.of());

        var normalized = normalizer.normalize("过去5天各停车区域进场量", modelUnderstanding);

        assertThat(normalized.requestedDimensions()).containsExactly("parking_zone");
    }

    @Test
    void keepsAlertTypePhrasingAsAnExplicitCategoryGrouping() {
        var modelUnderstanding = new AnalyticsModelClient.QuestionUnderstanding(
                "过去7天按告警类型分布",
                List.of("告警数量"),
                List.of(),
                null,
                List.of("category"),
                Map.of());

        var normalized = normalizer.normalize("过去7天按告警类型分布", modelUnderstanding);

        assertThat(normalized.requestedDimensions()).containsExactly("category");
    }
}
