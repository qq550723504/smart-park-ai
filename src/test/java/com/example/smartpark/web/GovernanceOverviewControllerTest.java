package com.example.smartpark.web;

import com.example.smartpark.governance.GovernanceOverview;
import com.example.smartpark.governance.GovernanceOverviewService;
import com.example.smartpark.operations.OperationsCapabilitiesSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernanceOverviewControllerTest {

    @Test
    void exposesTheSafeOverviewWithoutRoleSpecificRawDetails() {
        GovernanceOverviewService service = mock(GovernanceOverviewService.class);
        GovernanceOverview expected = new GovernanceOverview(
                Instant.EPOCH,
                new GovernanceOverview.ScenarioCounts(1, 1, 0, 0),
                new OperationsCapabilitiesSnapshot("mock", "mock", "none", false, false, false),
                new GovernanceOverview.BusinessCounts(0, 0, 0, 0),
                new GovernanceOverview.GovernanceCounts(0, 0, 0, 0, 0, null, null),
                List.of("演示角色，不是生产认证"));
        when(service.snapshot()).thenReturn(expected);

        GovernanceOverview actual = new GovernanceOverviewController(service).overview();

        assertThat(actual).isSameAs(expected);
        assertThat(actual.boundaries()).containsExactly("演示角色，不是生产认证");
    }
}
