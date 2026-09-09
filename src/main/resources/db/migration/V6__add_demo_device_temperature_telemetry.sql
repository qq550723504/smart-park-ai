-- Deterministic product-demo telemetry for Issue #60 contract verification.
-- DEMO DATA IS NOT PRODUCTION TELEMETRY. The table name preserves that boundary.

CREATE TABLE IF NOT EXISTS analytics.device_telemetry_demo_hourly_raw (
    device_id      varchar(64)   NOT NULL,
    building_id    varchar(32)   NOT NULL,
    device_type    varchar(48)   NOT NULL,
    telemetry_type varchar(32)   NOT NULL,
    unit           varchar(16)   NOT NULL,
    observed_at    timestamptz   NOT NULL,
    value          numeric(12,3) NOT NULL,
    quality        varchar(24)   NOT NULL,
    PRIMARY KEY (device_id, telemetry_type, observed_at)
);

-- Relative timestamps keep local demonstrations usable; values are a deterministic
-- fixture sequence. Deliberate gaps exercise PARTIAL without zero-fill/interpolation.
INSERT INTO analytics.device_telemetry_demo_hourly_raw
    (device_id, building_id, device_type, telemetry_type, unit, observed_at, value, quality)
SELECT fixture.device_id,
       fixture.building_id,
       'HVAC',
       'TEMPERATURE',
       '°C',
       date_trunc('hour', now()) - INTERVAL '47 hours' + make_interval(hours => h),
       CASE
           WHEN fixture.device_id = 'AC-B1-07' AND h >= 44 THEN 30.0 + (h - 44) * 0.5
           WHEN fixture.device_id = 'HUM-B2-11' THEN 23.0 + (h % 4) * 0.2
           ELSE 24.0 + (h % 5) * 0.1
       END,
       'GOOD'
FROM (VALUES
    ('AC-B1-07', 'B1'),
    ('HUM-B2-11', 'B2'),
    ('AC-B3-03', 'B3')
) AS fixture(device_id, building_id)
CROSS JOIN generate_series(0, 47) AS h
WHERE NOT (fixture.device_id = 'AC-B1-07' AND h IN (12, 31))
  AND NOT (fixture.device_id = 'HUM-B2-11' AND h = 28)
ON CONFLICT DO NOTHING;

CREATE OR REPLACE VIEW analytics.v_device_telemetry_hourly AS
SELECT device_id,
       building_id,
       device_type,
       telemetry_type,
       unit,
       date_trunc('hour', observed_at) AS hour_ts,
       value,
       quality,
       observed_at
FROM analytics.device_telemetry_demo_hourly_raw;

GRANT SELECT ON analytics.v_device_telemetry_hourly TO smartpark_analytics_ro;
REVOKE ALL ON analytics.device_telemetry_demo_hourly_raw FROM smartpark_analytics_ro;
