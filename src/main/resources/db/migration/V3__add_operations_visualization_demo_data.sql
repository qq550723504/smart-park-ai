-- Demo dimensions and measures used by the operations-analysis visualizations.
-- Raw tables stay outside the read-only allow-list; only the existing energy
-- view is extended with derived, queryable columns.

CREATE TABLE IF NOT EXISTS analytics.building_profile_raw (
    building_id       varchar(32)   PRIMARY KEY,
    building_name     varchar(96)   NOT NULL,
    area_sqm          numeric(12,2) NOT NULL,
    floor_count       integer       NOT NULL,
    map_x             numeric(8,3)  NOT NULL,
    map_y             numeric(8,3)  NOT NULL,
    daily_target_kwh  numeric(12,3) NOT NULL,
    occupancy_capacity integer       NOT NULL
);

CREATE TABLE IF NOT EXISTS analytics.building_occupancy_demo_hourly_raw (
    building_id    varchar(32) NOT NULL,
    occupied_at    timestamptz NOT NULL,
    occupancy_count integer     NOT NULL,
    PRIMARY KEY (building_id, occupied_at),
    CONSTRAINT fk_building_occupancy_profile
        FOREIGN KEY (building_id) REFERENCES analytics.building_profile_raw(building_id)
);

INSERT INTO analytics.building_profile_raw
    (building_id, building_name, area_sqm, floor_count, map_x, map_y, daily_target_kwh, occupancy_capacity)
VALUES
    ('B1', '创新中心', 32000.00, 18, 12.500, 35.000, 5200.000, 680),
    ('B2', '研发大厦', 28500.00, 16, 42.000, 24.000, 5000.000, 620),
    ('B3', '运营中心', 41000.00, 22, 70.500, 48.000, 5600.000, 820)
ON CONFLICT (building_id) DO UPDATE SET
    building_name = EXCLUDED.building_name,
    area_sqm = EXCLUDED.area_sqm,
    floor_count = EXCLUDED.floor_count,
    map_x = EXCLUDED.map_x,
    map_y = EXCLUDED.map_y,
    daily_target_kwh = EXCLUDED.daily_target_kwh,
    occupancy_capacity = EXCLUDED.occupancy_capacity;

INSERT INTO analytics.building_occupancy_demo_hourly_raw (building_id, occupied_at, occupancy_count)
SELECT
    'B' || b,
    ((CURRENT_DATE - 6 + d)::timestamp AT TIME ZONE 'Asia/Shanghai')
        + make_interval(hours => h),
    CASE
        WHEN h BETWEEN 8 AND 18 THEN 90 + b * 28 + ((d * 7 + h) % 22)
        WHEN h BETWEEN 19 AND 21 THEN 35 + b * 9 + ((d + h) % 10)
        ELSE 8 + b * 4 + ((d + h) % 6)
    END
FROM generate_series(1, 3) AS b,
     generate_series(0, 6) AS d,
     generate_series(0, 23) AS h
ON CONFLICT DO NOTHING;

-- Keep the original first six view columns stable for existing clients, then
-- append visualization dimensions and measures.
CREATE OR REPLACE VIEW analytics.v_energy_hourly AS
SELECT
    r.building_id,
    r.meter_id,
    date_trunc('hour', r.reading_at) AS hour_ts,
    SUM(r.kwh)                        AS kwh,
    AVG(r.baseline_kwh)               AS baseline_kwh,
    MAX(r.peak_kw)                    AS peak_kw,
    p.building_name,
    (r.reading_at AT TIME ZONE 'Asia/Shanghai')::date AS stat_date,
    EXTRACT(HOUR FROM r.reading_at AT TIME ZONE 'Asia/Shanghai')::integer AS hour_of_day,
    EXTRACT(ISODOW FROM r.reading_at AT TIME ZONE 'Asia/Shanghai')::integer AS day_of_week,
    p.area_sqm,
    p.map_x,
    p.map_y,
    MAX(o.occupancy_count)             AS occupancy_count,
    p.daily_target_kwh                 AS target_kwh
FROM analytics.energy_hourly_raw r
JOIN analytics.building_profile_raw p ON p.building_id = r.building_id
LEFT JOIN analytics.building_occupancy_demo_hourly_raw o
       ON o.building_id = r.building_id
      AND o.occupied_at = r.reading_at
GROUP BY r.building_id, r.meter_id, date_trunc('hour', r.reading_at),
         p.building_name, (r.reading_at AT TIME ZONE 'Asia/Shanghai')::date,
         EXTRACT(HOUR FROM r.reading_at AT TIME ZONE 'Asia/Shanghai'),
         EXTRACT(ISODOW FROM r.reading_at AT TIME ZONE 'Asia/Shanghai'),
         p.area_sqm, p.map_x, p.map_y, p.daily_target_kwh;

GRANT SELECT ON analytics.v_energy_hourly TO smartpark_analytics_ro;
