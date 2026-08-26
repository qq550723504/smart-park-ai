# Smart Park Docker Compose Local Runtime Design

## Goal

Provide a reproducible local Docker Compose runtime for the latest Smart Park
P1 code without putting credentials in the repository. The default command
must start a usable Mock/offline demo; the real analytics capability must be an
explicit opt-in that uses a dedicated PostgreSQL database.

## Scope

The change adds only local container orchestration and its build inputs:

- a Spring Boot backend image;
- a Vue/Vite frontend development image;
- a dedicated PostgreSQL analytics service with a named volume;
- `.env.example` and Compose documentation;
- health checks, dependency ordering, and focused verification.

It does not add production authentication, persistent application run stores,
TLS, an external DashScope proxy, or a shared business database.

## Runtime shape

`frontend -> backend -> analytics-postgres` is the only application path.
The backend never receives the PostgreSQL admin credentials for query
execution; Flyway uses the admin values and runtime analytics queries use the
fixed `smartpark_analytics_ro` role. The PostgreSQL service is dedicated to
analytics and is not reused by unrelated application data.

The default Compose profile starts the frontend, backend, and PostgreSQL
container while leaving `SMARTPARK_ANALYTICS_ENABLED=false` and
`SPRING_AI_DASHSCOPE_ENABLED=false`. This keeps the first run deterministic and
credential-free while still making the database available for inspection.

The `analytics` profile enables the real read-only analytics chain. It requires
the five analytics connection variables and a DashScope key; the backend must
fail clearly when those values are absent rather than silently falling back to
Mock analytics.

## Secrets and configuration

`.env.example` contains variable names, safe local defaults, and no secret
values. `.env` is ignored locally and Compose reads it through interpolation.
`AI_DASHSCOPE_API_KEY`, PostgreSQL admin credentials, and the read-only role
password are never written into a Dockerfile, image layer, command line, or
tracked file.

## Verification contract

- `docker compose config` succeeds with the example environment.
- Default Compose startup reaches backend and frontend health checks.
- `GET /api/operations/capabilities` reports analytics disabled in default
  mode and the backend remains usable without a model key.
- Analytics-profile configuration passes through the dedicated database
  variables and does not expose admin credentials to the runtime datasource.
- Backend tests and frontend production build remain green.
- The default and analytics-profile commands are documented separately.
