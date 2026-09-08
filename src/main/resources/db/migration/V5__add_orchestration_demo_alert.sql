-- One canonical action target shared with MockParkDataStore by the
-- cross-scenario orchestration demo. Keep identity and routing fields aligned.

INSERT INTO analytics.alert_fact_raw
    (alert_id, building_id, device_id, category, risk_level, occurred_at, status)
VALUES
    ('ALT-ORCH-ENERGY-B1-001', 'B1', 'DEV-ENERGY-B1-001', 'ENERGY', 'LOW',
     (((CURRENT_DATE - 1)::timestamp + TIME '10:12') AT TIME ZONE 'Asia/Shanghai'), 'OPEN')
ON CONFLICT (alert_id) DO UPDATE SET
    building_id = EXCLUDED.building_id,
    device_id = EXCLUDED.device_id,
    category = EXCLUDED.category,
    risk_level = EXCLUDED.risk_level,
    occurred_at = EXCLUDED.occurred_at,
    status = EXCLUDED.status;

INSERT INTO analytics.device_snapshot_raw
    (device_id, building_id, device_type, status, snapshot_at)
VALUES
    ('DEV-ENERGY-B1-001', 'B1', 'ENERGY_METER', 'ONLINE', now() - INTERVAL '2 hours')
ON CONFLICT (device_id) DO UPDATE SET
    building_id = EXCLUDED.building_id,
    device_type = EXCLUDED.device_type,
    status = EXCLUDED.status,
    snapshot_at = EXCLUDED.snapshot_at;
