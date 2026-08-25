-- The analytics data source is a dedicated database. PostgreSQL's PUBLIC role
-- applies to every login, so a read-only role cannot be isolated while PUBLIC
-- keeps database, schema, or object privileges. Revoke those ambient grants
-- first, then rebuild the analytics account from an explicit allow-list.
DO $$
BEGIN
    EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), 'smartpark_analytics_ro');
END
$$;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA analytics FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA analytics FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA analytics FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA analytics FROM PUBLIC;

-- Keep future migration-owned objects closed by default as well. The
-- application role only receives the view grants listed below.
ALTER DEFAULT PRIVILEGES REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES REVOKE ALL ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES REVOKE ALL ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES REVOKE ALL ON TYPES FROM PUBLIC;

ALTER ROLE smartpark_analytics_ro
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;

REVOKE ALL ON SCHEMA public FROM smartpark_analytics_ro;
REVOKE ALL ON SCHEMA analytics FROM smartpark_analytics_ro;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM smartpark_analytics_ro;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM smartpark_analytics_ro;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM smartpark_analytics_ro;
REVOKE ALL ON ALL TABLES IN SCHEMA analytics FROM smartpark_analytics_ro;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA analytics FROM smartpark_analytics_ro;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA analytics FROM smartpark_analytics_ro;

GRANT USAGE ON SCHEMA analytics TO smartpark_analytics_ro;
GRANT SELECT ON analytics.v_energy_hourly, analytics.v_alert_fact,
                analytics.v_device_snapshot, analytics.v_parking_daily
  TO smartpark_analytics_ro;

REVOKE ALL ON analytics.energy_hourly_raw, analytics.alert_fact_raw,
              analytics.device_snapshot_raw, analytics.parking_daily_raw
  FROM smartpark_analytics_ro;
