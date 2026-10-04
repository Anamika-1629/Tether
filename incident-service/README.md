# Tether Incident / Timeline Service (Member 2)

Spring Boot 3 / Java 17 / PostgreSQL. Port **8082**. Covers FR1, FR5, FR7.

## Run
```bash
docker compose up -d postgres        # from the repo root
cd services/incident-service
./mvnw spring-boot:run               # defaults match docker-compose.yml (incidentsync/incidentsync)
```
No `mvnw` yet? Run `mvn -N wrapper:wrapper` once in this folder (or copy the wrapper from another Tether service).
Flyway creates `incident` and `audit_event` on first start (history table `incident_schema_history`, so it won't clash with other services on the shared DB).

## Identity (temporary)
`X-Tenant-Id` (default `default`) and `X-Actor` (default `anonymous`) headers stand in for auth. Every query is scoped by tenant.

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
H='-H Content-Type:application/json -H X-Tenant-Id:acme'

curl -s -X POST localhost:8082/incidents $H -H 'X-Actor: alice' \
  -d '{"title":"Checkout API 500s","severity":"SEV2","owner":"alice"}'

curl -s localhost:8082/incidents -H 'X-Tenant-Id: acme'
curl -s localhost:8082/incidents/$ID -H 'X-Tenant-Id: acme'

curl -s -X PATCH localhost:8082/incidents/$ID $H -H 'X-Actor: bob' \
  -d '{"status":"INVESTIGATING","owner":"bob"}'

curl -s localhost:8082/incidents/$ID/audit -H 'X-Tenant-Id: acme'
```

## Audit log design
- Every POST/PATCH writes `audit_event` rows in the **same transaction** as the change.
- PATCH writes one row per field that actually changed (field, old, new, actor, timestamp). No-op patches write nothing.
- Append-only is enforced three ways: repository exposes only `save` + reads, entity is Hibernate `@Immutable`, and a Postgres trigger rejects UPDATE/DELETE/TRUNCATE even from raw SQL.
- Demo: `UPDATE audit_event SET actor='x';` -> `ERROR: audit_event is append-only`.

## Boundary with the Sync Service
This service owns structured fields (status/severity/owner). Free-text collaborative notes live in the Sync Service's CRDT doc, keyed by incident id. Only the UUID is shared.
