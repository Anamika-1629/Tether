import { config } from '../config.js'
import { request } from './client.js'

export const authApi = {
  login: (email, password) =>
    request(config.authUrl, '/auth/login', { method: 'POST', body: { email, password }, auth: false }),
  /** Pass organizationName to create an organization, or joinCode to join one. */
  register: (payload) => request(config.authUrl, '/auth/register', { method: 'POST', body: payload, auth: false }),
  members: () => request(config.authUrl, '/auth/tenant/members'),
}
