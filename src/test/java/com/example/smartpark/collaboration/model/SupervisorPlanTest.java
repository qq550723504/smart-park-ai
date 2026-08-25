package com.example.smartpark.collaboration.model;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupervisorPlanTest {
    @Test void deduplicatesDomainsAndCopiesAssignments() {
        SupervisorPlan plan = new SupervisorPlan("normalized", EnumSet.of(ExpertDomain.ENERGY, ExpertDomain.DEVICE),
                Map.of(ExpertDomain.ENERGY, "check consumption", ExpertDomain.DEVICE, "check offline equipment"), "two matching signals");
        assertThat(plan.selectedDomains()).containsExactlyInAnyOrder(ExpertDomain.ENERGY, ExpertDomain.DEVICE);
        assertThat(plan.assignments()).containsKeys(ExpertDomain.ENERGY, ExpertDomain.DEVICE);
    }

    @Test void requiresAtLeastOneDomainAndExactAssignments() {
        assertThatThrownBy(() -> new SupervisorPlan("q", EnumSet.noneOf(ExpertDomain.class), Map.of(), "reason"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SupervisorPlan("q", EnumSet.of(ExpertDomain.ENERGY), Map.of(), "reason"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
