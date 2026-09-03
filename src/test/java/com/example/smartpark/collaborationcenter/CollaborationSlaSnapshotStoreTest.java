package com.example.smartpark.collaborationcenter;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollaborationSlaSnapshotStoreTest {

    @Test
    void aggregatesAllSlaStatesAndSkipsSamplesWithinTheThirtySecondWindow() {
        CollaborationSlaSnapshotStore store = new CollaborationSlaSnapshotStore();
        Instant first = Instant.parse("2026-09-02T10:00:00Z");

        assertThat(store.recordIfDue(first, items())).isTrue();
        assertThat(store.recordIfDue(first.plusSeconds(29), items())).isFalse();
        assertThat(store.recordIfDue(first.plusSeconds(30), items())).isTrue();

        CollaborationSlaSnapshot snapshot = store.list(120).get(0);
        assertThat(snapshot).isEqualTo(new CollaborationSlaSnapshot(first, 5, 1, 1, 1, 1, 1));
        assertThat(store.list(120)).hasSize(2);
    }

    @Test
    void retainsOnlyTheNewestOneHundredAndTwentySamplesInAscendingOrder() {
        CollaborationSlaSnapshotStore store = new CollaborationSlaSnapshotStore();
        Instant start = Instant.parse("2026-09-02T10:00:00Z");

        for (int index = 0; index < 121; index++) {
            store.recordIfDue(start.plusSeconds(index * 30L), List.of());
        }

        List<CollaborationSlaSnapshot> snapshots = store.list(120);
        assertThat(snapshots).hasSize(120);
        assertThat(snapshots.get(0).capturedAt()).isEqualTo(start.plusSeconds(30));
        assertThat(snapshots.get(119).capturedAt()).isEqualTo(start.plusSeconds(120 * 30L));
        assertThatThrownBy(() -> snapshots.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidLimits() {
        CollaborationSlaSnapshotStore store = new CollaborationSlaSnapshotStore();

        assertThatThrownBy(() -> store.list(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.list(121)).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<CollaborationWorkItem> items() {
        List<CollaborationWorkItem> items = new ArrayList<>();
        items.add(item("on-track", CollaborationWorkItem.SlaState.ON_TRACK));
        items.add(item("due-soon", CollaborationWorkItem.SlaState.DUE_SOON));
        items.add(item("overdue", CollaborationWorkItem.SlaState.OVERDUE));
        items.add(item("completed", CollaborationWorkItem.SlaState.COMPLETED));
        items.add(item("not-applicable", CollaborationWorkItem.SlaState.NOT_APPLICABLE));
        return items;
    }

    private static CollaborationWorkItem item(String id, CollaborationWorkItem.SlaState state) {
        return new CollaborationWorkItem(
                "CUSTOMER_TICKET:" + id,
                CollaborationWorkItem.Source.CUSTOMER_TICKET,
                CollaborationWorkItem.Status.WAITING_AGENT,
                CollaborationWorkItem.Priority.NORMAL,
                id,
                id,
                null,
                null,
                null,
                Instant.parse("2026-09-02T09:00:00Z"),
                Instant.parse("2026-09-02T08:00:00Z"),
                Instant.parse("2026-09-02T12:00:00Z"),
                state,
                "customer",
                null);
    }
}
