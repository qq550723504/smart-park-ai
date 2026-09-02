package com.example.smartpark.analytics.report;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationsDailyReportDefinitionTest {

    @Test
    void exposesOnlyTheThreeStableSectionsInExecutionOrder() {
        assertThat(OperationsDailyReportDefinition.sections())
                .extracting(OperationsReportSection::id)
                .containsExactly("ENERGY_BASELINE", "PARKING_UTILIZATION", "ALERT_RISK");
        assertThat(OperationsDailyReportDefinition.sections())
                .allSatisfy(section -> assertThat(section.question()).isNotBlank());
    }

    @Test
    void sectionRejectsBlankContractFields() {
        assertThatThrownBy(() -> new OperationsReportSection("", "title", "question"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperationsReportSection("id", " ", "question"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperationsReportSection("id", "title", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
