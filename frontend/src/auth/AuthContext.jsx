import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { authApi } from '../api/auth.js'
import { configureApi } from '../api/client.js'

const STORAGE_KEY = 'tether.session'
const AuthContext = createContext(null)

/**
 * The session lives in sessionStorage: it survives a page reload but is private to the tab,
 * so two tabs can be signed in as two different responders (handy for the live demo).
 */
function loadSession() {
  try {
    const session = JSON.parse(sessionStorage.getItem(STORAGE_KEY))
    return session && new Date(session.expiresAt) > new Date() ? session : null
  } catch {
    return null
  }
}

function toSession(auth) {
  return { token: auth.accessToken, expiresAt: auth.expiresAt, user: auth.user, tenant: auth.tenant }
}

export function AuthProvider({ children }) {
  const [session, setSession] = useState(loadSession)
  const [notice, setNotice] = useState(null)

  const save = useCallback((next) => {
    try {
      if (next) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next))
      else sessionStorage.removeItem(STORAGE_KEY)
    } catch {
      // Storage blocked (private mode): the session still works until the tab is closed
    }
    setSession(next)
  }, [])

  const logout = useCallback((message = null) => {
    setNotice(message)
    save(null)
  }, [save])

  // Every API call reads the current token; any 401 signs the user out
  useEffect(() => {
    configureApi({
      tokenProvider: () => loadSession()?.token ?? null,
      unauthorizedHandler: () => logout('Your session expired. Please sign in again.'),
    })
  }, [logout])

  // Sign out exactly when the token expires
  useEffect(() => {
    if (!session) return
    const ms = new Date(session.expiresAt).getTime() - Date.now()
    const timer = setTimeout(() => logout('Your session expired. Please sign in again.'), Math.max(ms, 0))
    return () => clearTimeout(timer)
  }, [session, logout])

  const value = useMemo(() => ({
    session,
    notice,
    login: async (email, password) => { setNotice(null); save(toSession(await authApi.login(email, password))) },
    register: async (payload) => { setNotice(null); save(toSession(await authApi.register(payload))) },
    logout,
  }), [session, notice, save, logout])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  return useContext(AuthContext)
}
