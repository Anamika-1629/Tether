# Tether 

**Real-Time Collaborative Incident Response Platform**

A cloud-native tool that lets multiple engineers (SRE, backend, DBA, etc.) work on the same live production incident simultaneously — adding notes, updating status, assigning owners, pasting logs — without overwriting each other's changes or losing work when a connection drops mid-session.

## Why

During an outage, teams typically fall back to Slack threads or a shared doc. Neither is built for this: Slack has no structured timeline, and shared docs offer no real conflict-free editing, no presence awareness, and no resilience guarantees appropriate for a tool that's meant to keep working *while everything else is on fire*.

This project uses conflict-free replicated data types (CRDTs) for concurrent editing, WebSocket-based live presence, an event-sourced audit trail for postmortems, and a horizontally scalable, self-observable cloud deployment — so the incident tool itself never becomes the next point of failure.

## Features

- **Conflict-free collaborative timeline** — multiple responders edit the same incident timeline at once; concurrent edits merge automatically with no overwrite/lock errors.
- **Offline resilience** — edits made while disconnected (laptop sleep, flaky wifi) are preserved locally and merge cleanly on reconnect.
- **Live presence** — see who else is currently viewing or editing the incident, similar to collaborative doc editors.
- **Event-sourced audit trail** — every change (status update, assignment, note, log paste) is recorded in order, so a clean postmortem timeline can be reconstructed automatically.
- **Self-observable infrastructure** — the platform monitors its own sync latency, active sessions, and reconnect success rate, so degradation is visible before it becomes an outage of the outage tool.

## Architecture

```
┌─────────────┐        WebSocket/STOMP        ┌──────────────────┐
│   React      │ ───────────────────────────▶ │   Sync Service    │
│   Frontend   │ ◀─────────────────────────── │  (Spring Boot)    │
│  (Yjs CRDT)  │                               └─────────┬─────────┘
└─────────────┘                                          │
                                                          │ Redis Pub/Sub
                                                          │ (presence, cursors)
                                              ┌───────────▼─────────┐
                                              │       Redis          │
                                              └───────────────────────┘
                                                          │
                                              ┌───────────▼─────────┐
                                              │ Incident/Timeline   │
                                              │ Service (Spring Boot)│
                                              └───────────┬─────────┘
                                                          │
                                              ┌───────────▼─────────┐
                                              │     PostgreSQL       │
                                              │ (durable state +     │
                                              │  event-sourced log)  │
                                              └───────────────────────┘

              ┌────────────────────────┐
              │ Auth/Tenant Service     │  (Spring Boot, Java 17)
              └────────────────────────┘

Observability: Prometheus + Grafana across all services
```

## Tech Stack

| Layer | Technology |
|---|---|
| Frontend | React, Yjs (CRDT sync library), WebSocket client, Tailwind CSS |
| Backend | Spring Boot (Sync Service, Incident/Timeline Service, Auth/Tenant Service), Java 17 |
| Real-Time Communication | WebSocket/STOMP, Redis Pub/Sub (presence, live cursors) |
| Data Storage | PostgreSQL (durable state, event-sourced change log), Redis (ephemeral session/presence state) |
| Cloud & Infrastructure | Docker, AWS ECS/EKS, AWS Application Load Balancer, Terraform |
| Observability | Prometheus, Grafana (sync latency, active sessions, reconnect success rate) |
| CI/CD | GitHub Actions |

## Requirements

### Functional Requirements
- FR1: Users can create an incident and add it to a shared timeline.
- FR2: Multiple users can concurrently edit timeline entries (status, assignee, notes, pasted logs) without overwriting each other.
- FR3: Edits made while offline are queued locally and merge automatically on reconnect.
- FR4: Users can see which other users are currently active on an incident (presence).
- FR5: Every change to an incident is recorded as an immutable, ordered event for later audit/postmortem review.
- FR6: Users can log in and are scoped to their organization/tenant (multi-tenancy).
- FR7: Incident status, ownership, and severity can be updated in real time and reflected to all connected clients.

### Non-Functional Requirements
- NFR1: The system must remain usable during partial network failure — no data loss on client disconnect/reconnect.
- NFR2: Sync latency (edit-to-broadcast) should be observable and reported via Prometheus/Grafana.
- NFR3: The backend must be horizontally scalable (stateless services behind a load balancer, session/presence state in Redis rather than in-process memory).
- NFR4: Infrastructure must be defined as code (Terraform) and deployable via CI/CD (GitHub Actions).
- NFR5: The system must be containerized (Docker) and deployable to AWS ECS/EKS.

## Project Structure

```
incident-sync/
├── frontend/                  # React + Yjs client
├── services/
│   ├── sync-service/          # WebSocket/STOMP + Yjs sync backend
│   ├── incident-service/      # Incident/timeline CRUD + event log
│   └── auth-service/          # Auth/tenant management
├── infra/
│   └── terraform/             # AWS infra as code
├── observability/
│   ├── prometheus/
│   └── grafana/
├── docker-compose.yml         # Local dev environment
└── README.md
```

## Getting Started (Local Dev)

Prerequisites: Docker, Docker Compose, Java 17, Node.js 18+

```bash
# Clone the repo
git clone <your-repo-url>
cd incident-sync

# Start local infra (Postgres, Redis)
docker compose up -d

# Backend services (run each in its own terminal)
cd services/sync-service && ./mvnw spring-boot:run
cd services/incident-service && ./mvnw spring-boot:run
cd services/auth-service && ./mvnw spring-boot:run

# Frontend
cd frontend
npm install
npm run dev
```

## Roadmap / Capstone Milestones

- [ ] Core incident/timeline CRUD (Incident Service + Postgres)
- [ ] WebSocket/STOMP sync layer with Yjs integration
- [ ] Presence via Redis Pub/Sub
- [ ] Event-sourced audit log + postmortem export
- [ ] Auth/tenant service
- [ ] Dockerize all services + docker-compose for local dev
- [ ] Terraform infra for AWS deployment
- [ ] Prometheus/Grafana dashboards (sync latency, active sessions, reconnect success rate)
- [ ] CI/CD pipeline (GitHub Actions)

## License

TBD
