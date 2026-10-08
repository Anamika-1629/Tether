# Demo scripts (Windows)

Start everything for a live demo:

```
scripts\start-demo.cmd            (double-click, or run from a terminal)
scripts\start-demo.cmd -Reset     (same, but starts from an empty database)
```

It checks Java 17+, Node and Docker, starts Postgres and Redis, then auth (8081), incident (8082),
sync (8083) and the frontend (3000) in their own windows, waits for each to be healthy, and opens
http://localhost:3000 in two browser tabs. Each tab keeps its own login, so the two tabs act as two responders.

Stop it with `scripts\stop-demo.cmd` (add `-KeepInfra` to leave Postgres and Redis running).

Needs Docker Desktop running, and ports 5432, 6379, 8081, 8082, 8083 and 3000 free. The first run
downloads Maven and npm dependencies and can take a few minutes. Windows may ask to allow Java
through the firewall: allow it for private networks.
