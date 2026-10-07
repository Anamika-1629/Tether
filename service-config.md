# Tether Service Configuration

This document contains the shared service configuration and infrastructure details required for local development and integration of the Tether project.

## 1. Services

| Service | Port | Base URL | Status |
|---|---:|---|---|
| Frontend | 3000 | `http://localhost:3000` | Working (tested locally) |
| Auth Service | 8081 | `http://localhost:8081` | Working (tested locally) |
| Incident Service | 8082 | `http://localhost:8082` | Working |
| Sync Service | 8083 | `http://localhost:8083` | Pending |

## 2. Shared Infrastructure

### PostgreSQL

**Provider:** Supabase

Supabase provides the shared PostgreSQL database for the Tether services. The Incident Service has been configured and tested successfully with the shared Supabase database.

- Database provider: Supabase
- Database type: PostgreSQL
- Incident Service: Connected and tested
- Auth Service: Ready (same `DB_*` variables; tested on local Postgres alongside the Incident Service, Supabase connection pending)
- Sync Service: Pending

### Redis

**Provider:** Upstash

Upstash provides the shared Redis instance intended for the services requiring Redis-based functionality, particularly the Sync Service.

- Redis provider: Upstash
- Instance: Configured
- Sync Service integration: Pending

## 3. Current Status

- [x] Supabase PostgreSQL instance configured
- [x] Incident Service connected to Supabase
- [x] Incident Service database connection tested
- [x] Upstash Redis instance configured
- [ ] Auth Service connected to shared infrastructure
- [ ] Sync Service connected to shared infrastructure
- [x] Frontend connected to Auth and Incident services (Sync Service via the dev stand-in in `frontend/sync-dev-server` until the real one lands)
- [ ] End-to-end integration testing