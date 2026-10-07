/** Error carrying the HTTP status and the backend's {"error": ..., "fields": ...} body. */
export class ApiError extends Error {
  constructor(status, message, fields) {
    super(message)
    this.status = status
    this.fields = fields
  }
}

let getToken = () => null
let onUnauthorized = () => {}

/** Wired up by AuthProvider: where to read the JWT, and what to do when a service rejects it. */
export function configureApi({ tokenProvider, unauthorizedHandler }) {
  getToken = tokenProvider
  onUnauthorized = unauthorizedHandler
}

/** fetch() + JSON + "Authorization: Bearer <jwt>" + consistent errors. */
export async function request(baseUrl, path, { method = 'GET', body, auth = true } = {}) {
  const headers = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const token = auth ? getToken() : null
  if (token) headers.Authorization = `Bearer ${token}`

  let res
  try {
    res = await fetch(`${baseUrl}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  } catch {
    throw new ApiError(0, `Can't reach ${new URL(baseUrl).host}. Is the service running?`)
  }

  const data = res.status === 204 ? null : await res.json().catch(() => null)
  if (!res.ok) {
    if (res.status === 401 && token) onUnauthorized()
    throw new ApiError(res.status, data?.error ?? `Request failed (${res.status})`, data?.fields)
  }
  return data
}
