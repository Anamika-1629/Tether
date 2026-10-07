# Tether Incident / Timeline Service (Member 2)

Spring Boot 3 / Java 17 / PostgreSQL. Port **8082**. Covers FR1, FR5, FR7.

## Run
```bash
docker compose up -d postgres        # from the repo root
cd incident-service
./mvnw spring-boot:run               # defaults match docker-compose.yml (incidentsync/incidentsync)
```
Flyway creates `incident` and `audit_event` on first start (history table `incident_schema_history`, so it won't clash with other services on the shared DB).

## Authentication
Every endpoint requires `Authorization: Bearer <jwt>` issued by **auth-service** (8081). Tenant and actor come **only** from the verified token (`tenantId` and `email` claims); the old `X-Tenant-Id` / `X-Actor` headers are ignored and a request with no token gets `401`.

| Variable | Default | Notes |
|---|---|---|
| `JWT_SECRET` | dev-only value (same as auth-service) | **Must equal auth-service's value.** At least 32 bytes or startup fails. |
| `JWT_ISSUER` | `tether-auth` | Tokens from any other issuer are rejected |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Comma-separated frontend origins |

A token is rejected (401) if the signature is wrong, it is expired, the issuer differs, or it has no `tenantId` claim. A tenant can never see another tenant's incidents (404).

Get a token: `curl -s -X POST localhost:8081/auth/login -H 'Content-Type: application/json' -d '{"email":"...","password":"..."}'` and use `accessToken`.

## Endpoints
| Method | Path | Notes |
|---|---|---|
| POST | /incidents | `title`, `severity` required; `status` defaults OPEN |
| GET | /incidents | optional `?status=OPEN` |
| GET | /incidents/{id} | |
| PATCH | /incidents/{id} | any of `status`, `severity`, `owner` |
| GET | /incidents/{id}/audit | timeline feed, oldest first (read-only) |

Enums: status `OPEN|INVESTIGATING|MITIGATED|RESOLVED`, severity `SEV1..SEV4`.

## curl
```bash
TOKEN=<accessToken from auth-service /auth/login>
H="-H Content-Type:application/json -H Authorization:Bearer\ $TOKEN"

curl -s -X POST localhost:8082/incidents $H \
  -d '{"title":"Checkout API 500s","severity":"SEV2","owner":"alice"}'

curl -s localhost:8082/incidents -H "Authorization: Bearer $TOKEN"
curl -s localhost:8082/incidents/$ID -H "Authorization: Bearer $TOKEN"

curl -s -X PATCH localhost:8082/incidents/$ID $H \
  -d '{"status":"INVESTIGATING","owner":"bob"}'

curl -s localhost:8082/incidents/$ID/audit -H "Authorization: Bearer $TOKEN"
```

Tests: `./mvnw test` (in-memory H2, no Docker needed).

## Audit log design
- Every POST/PATCH writes `audit_event` rows in the **same transaction** as the change.
- PATCH writes one row per field that actually changed (field, old, new, actor, timestamp). No-op patches write nothing.
- Append-only is enforced three ways: repository exposes only `save` + reads, entity is Hibernate `@Immutable`, and a Postgres trigger rejects UPDATE/DELETE/TRUNCATE even from raw SQL.
- Demo: `UPDATE audit_event SET actor='x';` -> `ERROR: audit_event is append-only`.

## Boundary with the Sync Service
This service owns structured fields (status/severity/owner). Free-text collaborative notes live in the Sync Service's CRDT doc, keyed by incident id. Only the UUID is shared.
