-- The energy view is one row per meter-hour while daily_target_kwh belongs to
-- the building. Expose the hourly meter grain so approved metrics can allocate
-- each building target exactly once per hour, regardless of meter count.

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
    p.daily_target_kwh                 AS target_kwh,
    COUNT(*) OVER (
        PARTITION BY r.building_id, date_trunc('hour', r.reading_at)
    )                                  AS meter_count
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
