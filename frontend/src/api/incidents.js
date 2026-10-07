import { config } from '../config.js'
import { request } from './client.js'

export const STATUSES = ['OPEN', 'INVESTIGATING', 'MITIGATED', 'RESOLVED']
export const SEVERITIES = ['SEV1', 'SEV2', 'SEV3', 'SEV4']

export const incidentsApi = {
  list: (status) => request(config.incidentUrl, `/incidents${status ? `?status=${status}` : ''}`),
  get: (id) => request(config.incidentUrl, `/incidents/${id}`),
  create: (incident) => request(config.incidentUrl, '/incidents', { method: 'POST', body: incident }),
  /** Any of status, severity, owner. */
  update: (id, changes) => request(config.incidentUrl, `/incidents/${id}`, { method: 'PATCH', body: changes }),
  audit: (id) => request(config.incidentUrl, `/incidents/${id}/audit`),
}
