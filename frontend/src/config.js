/** Backend locations. Override with VITE_* variables (see .env.example); the defaults match service-config.md. */
export const config = {
  authUrl: import.meta.env.VITE_AUTH_URL ?? 'http://localhost:8081',
  incidentUrl: import.meta.env.VITE_INCIDENT_URL ?? 'http://localhost:8082',
  syncUrl: import.meta.env.VITE_SYNC_URL ?? 'ws://localhost:8083/sync',
}
