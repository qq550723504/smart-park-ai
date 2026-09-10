-- Keep the one cross-page demo action target aligned with the existing
-- alert-workflow risk gate. HIGH guarantees that the existing workflow pauses
-- for an explicit human decision before its idempotent work-order port runs.
UPDATE analytics.alert_fact_raw
SET risk_level = 'HIGH'
WHERE alert_id = 'ALT-ORCH-ENERGY-B1-001'
  AND building_id = 'B1'
  AND device_id = 'DEV-ENERGY-B1-001'
  AND category = 'ENERGY';
