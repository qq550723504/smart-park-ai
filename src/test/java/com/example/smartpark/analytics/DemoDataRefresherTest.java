package com.example.smartpark.analytics;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoDataRefresherTest {

    @Test
    void startsRefreshingImmediatelyWhenEnabled() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        Connection connection = connection(new ArrayList<>(), new AtomicBoolean(),
                new AtomicBoolean(), null);
        DemoDataRefresher refresher = new DemoDataRefresher(() -> {
            opened.countDown();
            return connection;
        }, Duration.ofHours(1));
        try {
            refresher.start();
            assertThat(opened.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            refresher.shutdown();
        }
    }

    @Test
    void refreshUsesExactEnergyFixtureKeysAndCommitsAllChangesTogether() throws Exception {
        List<String> sql = new ArrayList<>();
        AtomicBoolean committed = new AtomicBoolean();
        AtomicBoolean autoCommitDisabled = new AtomicBoolean();
        Connection connection = connection(sql, committed, autoCommitDisabled, null);

        DemoDataRefresher refresher = new DemoDataRefresher(() -> connection, Duration.ofHours(1));
        try {
            refresher.refreshOnce();
        } finally {
            refresher.shutdown();
        }

        assertThat(autoCommitDisabled).isTrue();
        assertThat(committed).isTrue();
        assertThat(sql).anyMatch(statement -> statement.contains(
                "meter_id IN ('MTR-1-1', 'MTR-1-2', 'MTR-2-1', 'MTR-2-2', 'MTR-3-1', 'MTR-3-2')"));
        assertThat(sql).anyMatch(statement -> statement.contains(
                "DELETE FROM analytics.building_occupancy_demo_hourly_raw"));
        assertThat(sql).anyMatch(statement -> statement.contains(
                "INSERT INTO analytics.building_occupancy_demo_hourly_raw"));
        assertThat(sql).anyMatch(statement -> statement.contains("CURRENT_DATE - 6 + d"));
        assertThat(sql).noneMatch(statement -> statement.contains("meter_id ~"));
    }

    @Test
    void refreshRollsBackWhenAnyFixtureStatementFails() {
        AtomicBoolean committed = new AtomicBoolean();
        AtomicBoolean rolledBack = new AtomicBoolean();
        Connection connection = connection(new ArrayList<>(), committed, new AtomicBoolean(), rolledBack);

        DemoDataRefresher refresher = new DemoDataRefresher(() -> connection, Duration.ofHours(1));
        try {
            assertThatThrownBy(refresher::refreshOnce)
                    .isInstanceOf(SQLException.class);
        } finally {
            refresher.shutdown();
        }

        assertThat(committed).isFalse();
        assertThat(rolledBack).isTrue();
    }

    private static Connection connection(List<String> sql, AtomicBoolean committed,
                                         AtomicBoolean autoCommitDisabled, AtomicBoolean rolledBack) {
        AtomicBoolean currentAutoCommit = new AtomicBoolean(true);
        AtomicBoolean failSecondStatement = new AtomicBoolean(rolledBack != null);
        int[] executions = {0};
        Statement statement = (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                    if ("executeUpdate".equals(method.getName())) {
                        executions[0]++;
                        if (failSecondStatement.get() && executions[0] == 2) throw new SQLException("fixture failure");
                        sql.add((String) args[0]);
                        return 1;
                    }
                    return defaultValue(method.getReturnType());
                });
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "createStatement" -> statement;
                        case "getAutoCommit" -> currentAutoCommit.get();
                        case "setAutoCommit" -> {
                            currentAutoCommit.set((Boolean) args[0]);
                            if (!currentAutoCommit.get()) autoCommitDisabled.set(true);
                            yield null;
                        }
                        case "commit" -> {
                            committed.set(true);
                            yield null;
                        }
                        case "rollback" -> {
                            if (rolledBack != null) rolledBack.set(true);
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
}
