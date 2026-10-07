import { useState } from 'react'
import { Link, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext.jsx'

export default function Layout() {
  const { session, logout } = useAuth()
  const { user, tenant } = session
  const [copied, setCopied] = useState(false)

  const copyJoinCode = async () => {
    try {
      await navigator.clipboard.writeText(tenant.joinCode)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // Clipboard blocked: the code is visible anyway
    }
  }

  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-10 border-b border-slate-800 bg-slate-950/90 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center gap-4 px-4 py-3">
          <Link to="/" className="flex items-center gap-2 text-lg font-bold tracking-tight">
            <span aria-hidden>🪢</span> Tether
          </Link>
          <span className="rounded-md bg-slate-800 px-2 py-1 text-xs text-slate-300">{tenant.name}</span>
          {tenant.joinCode && (
            <button
              onClick={copyJoinCode}
              title="Teammates use this code to join your organization"
              className="hidden rounded-md border border-dashed border-slate-700 px-2 py-1 font-mono text-xs text-slate-400 hover:border-slate-500 hover:text-slate-200 sm:block"
            >
              {copied ? 'Copied!' : `Join code: ${tenant.joinCode}`}
            </button>
          )}
          <div className="ml-auto flex items-center gap-3 text-sm">
            <span className="text-slate-300">
              {user.displayName} <span className="text-slate-500">· {user.role.toLowerCase()}</span>
            </span>
            <button onClick={() => logout()} className="rounded-md px-2 py-1 text-slate-400 hover:bg-slate-800 hover:text-slate-100">
              Sign out
            </button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  )
}
