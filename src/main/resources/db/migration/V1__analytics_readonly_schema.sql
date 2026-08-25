-- Smart park read-only analytics boundary.
-- Admin/init account owns the objects; smartpark_analytics_ro may only SELECT
-- the four whitelisted analytics views. Night is fixed to 22:00–06:00.

CREATE SCHEMA IF NOT EXISTS analytics;

-- NOTE: no global REVOKE on the public schema — this database may be shared
-- with other applications, so their privileges must stay untouched. The
-- analytics read-only role is restricted explicitly below instead.

CREATE TABLE IF NOT EXISTS analytics.energy_hourly_raw (
    building_id  varchar(32)   NOT NULL,
    meter_id     varchar(64)   NOT NULL,
    reading_at   timestamptz   NOT NULL,
    kwh          numeric(12,3) NOT NULL,
    baseline_kwh numeric(12,3) NOT NULL,
    peak_kw      numeric(12,3) NOT NULL,
    PRIMARY KEY (building_id, meter_id, reading_at)
);

CREATE TABLE IF NOT EXISTS analytics.alert_fact_raw (
    alert_id    varchar(64) PRIMARY KEY,
    building_id varchar(32)   NOT NULL,
    device_id   varchar(64)   NOT NULL,
    category    varchar(48)   NOT NULL,
    risk_level  varchar(16)   NOT NULL,
    occurred_at timestamptz   NOT NULL,
    status      varchar(24)   NOT NULL
);

CREATE TABLE IF NOT EXISTS analytics.device_snapshot_raw (
    device_id   varchar(64) PRIMARY KEY,
    building_id varchar(32)  NOT NULL,
    device_type varchar(48)  NOT NULL,
    status      varchar(24)  NOT NULL,
    snapshot_at timestamptz  NOT NULL
);

CREATE TABLE IF NOT EXISTS analytics.parking_daily_raw (
    stat_date      date        NOT NULL,
    parking_zone   varchar(48) NOT NULL,
    entries        integer     NOT NULL,
    peak_occupancy integer     NOT NULL,
    capacity       integer     NOT NULL,
    PRIMARY KEY (stat_date, parking_zone)
);

-- Deterministic demo facts: three buildings, several days of hourly energy
-- readings (including night hours), alerts, devices and parking stats.
INSERT INTO analytics.energy_hourly_raw (building_id, meter_id, reading_at, kwh, baseline_kwh, peak_kw)
SELECT
    'B' || b,
    'MTR-' || b || '-' || m,
    TIMESTAMP '2026-08-20 00:00:00+08' + make_interval(hours => (d * 24 + h)),
    CASE WHEN h >= 22 OR h < 6 THEN 4.5 + d + b ELSE 18.0 + d * 2 + b END + m,
    15.0 + b * 2,
    CASE WHEN h BETWEEN 9 AND 19 THEN 42.0 + b * 3 ELSE 12.0 + b END
FROM generate_series(1, 3) AS b,
     generate_series(1, 2) AS m,
     generate_series(0, 4) AS d,
     generate_series(0, 23) AS h
ON CONFLICT DO NOTHING;

INSERT INTO analytics.alert_fact_raw (alert_id, building_id, device_id, category, risk_level, occurred_at, status) VALUES
    ('ALT-TEMP-001', 'B1', 'AC-B1-07', 'TEMPERATURE', 'HIGH',   TIMESTAMPTZ '2026-08-22 09:15:00+08', 'OPEN'),
    ('ALT-PWR-002',  'B1', 'PWR-B1-02', 'POWER',      'LOW',    TIMESTAMPTZ '2026-08-22 14:40:00+08', 'RESOLVED'),
    ('ALT-HUM-003',  'B2', 'HUM-B2-11', 'HUMIDITY',   'MEDIUM', TIMESTAMPTZ '2026-08-23 03:05:00+08', 'OPEN'),
    ('ALT-DOOR-004', 'B2', 'DR-B2-01',  'ACCESS',     'HIGH',   TIMESTAMPTZ '2026-08-23 22:30:00+08', 'OPEN'),
    ('ALT-TEMP-005', 'B3', 'AC-B3-03',  'TEMPERATURE', 'LOW',   TIMESTAMPTZ '2026-08-24 11:20:00+08', 'RESOLVED')
ON CONFLICT DO NOTHING;

INSERT INTO analytics.device_snapshot_raw (device_id, building_id, device_type, status, snapshot_at) VALUES
    ('AC-B1-07',  'B1', 'HVAC',       'ONLINE',       TIMESTAMPTZ '2026-08-24 08:00:00+08'),
    ('PWR-B1-02', 'B1', 'POWER_METER','ONLINE',       TIMESTAMPTZ '2026-08-24 08:00:00+08'),
    ('LFT-B1-01', 'B1', 'ELEVATOR',   'OFFLINE',      TIMESTAMPTZ '2026-08-24 08:00:00+08'),
    ('HUM-B2-11', 'B2', 'HVAC',       'DEGRADED',     TIMESTAMPTZ '2026-08-24 08:00:00+08'),
    ('DR-B2-01',  'B2', 'ACCESS',     'ONLINE',       TIMESTAMPTZ '2026-08-24 08:00:00+08'),
    ('AC-B3-03',  'B3', 'HVAC',       'OFFLINE',      TIMESTAMPTZ '2026-08-24 08:00:00+08'),
    ('CAM-B3-05', 'B3', 'CAMERA',     'ONLINE',       TIMESTAMPTZ '2026-08-24 08:00:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO analytics.parking_daily_raw (stat_date, parking_zone, entries, peak_occupancy, capacity) VALUES
    (DATE '2026-08-21', 'ZONE-A', 812, 340, 400),
    (DATE '2026-08-21', 'ZONE-B', 455, 180, 250),
    (DATE '2026-08-22', 'ZONE-A', 876, 388, 400),
    (DATE '2026-08-22', 'ZONE-B', 462, 205, 250),
    (DATE '2026-08-23', 'ZONE-A', 901, 397, 400),
    (DATE '2026-08-23', 'ZONE-B', 470, 214, 250),
    (DATE '2026-08-24', 'ZONE-A', 604, 266, 400),
    (DATE '2026-08-24', 'ZONE-B', 310, 141, 250)
ON CONFLICT DO NOTHING;

-- Whitelisted analysis views; owners keep full control, ro role gets SELECT.
CREATE OR REPLACE VIEW analytics.v_energy_hourly AS
SELECT
    building_id,
    meter_id,
    date_trunc('hour', reading_at) AS hour_ts,
    SUM(kwh)                        AS kwh,
    AVG(baseline_kwh)               AS baseline_kwh,
    MAX(peak_kw)                    AS peak_kw
FROM analytics.energy_hourly_raw
GROUP BY building_id, meter_id, date_trunc('hour', reading_at);

CREATE OR REPLACE VIEW analytics.v_alert_fact AS
SELECT
    alert_id,
    building_id,
    device_id,
    category,
    risk_level,
    occurred_at,
    status
FROM analytics.alert_fact_raw;

CREATE OR REPLACE VIEW analytics.v_device_snapshot AS
SELECT
    d.device_id,
    d.building_id,
    d.device_type,
    d.status,
    (SELECT COUNT(*) FROM analytics.alert_fact_raw a
      WHERE a.device_id = d.device_id AND a.status <> 'RESOLVED') AS open_alert_count,
    d.snapshot_at
FROM analytics.device_snapshot_raw d;

CREATE OR REPLACE VIEW analytics.v_parking_daily AS
SELECT
    stat_date,
    parking_zone,
    entries,
    peak_occupancy,
    capacity,
    ROUND(peak_occupancy * 100.0 / NULLIF(capacity, 0), 2) AS utilization_pct
FROM analytics.parking_daily_raw;

-- Read-only application role: login account with SELECT on the four views only.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'smartpark_analytics_ro') THEN
        EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', 'smartpark_analytics_ro', '${analyticsRoPassword}');
    END IF;
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), 'smartpark_analytics_ro');
END
$$;

REVOKE CREATE ON SCHEMA public FROM smartpark_analytics_ro;
GRANT USAGE ON SCHEMA analytics TO smartpark_analytics_ro;
GRANT SELECT ON analytics.v_energy_hourly, analytics.v_alert_fact,
                analytics.v_device_snapshot, analytics.v_parking_daily
  TO smartpark_analytics_ro;
REVOKE ALL ON analytics.energy_hourly_raw, analytics.alert_fact_raw,
              analytics.device_snapshot_raw, analytics.parking_daily_raw
  FROM smartpark_analytics_ro;
