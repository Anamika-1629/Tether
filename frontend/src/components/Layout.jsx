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
    <div className="min-h-screen" style={{ background: 'var(--color-bg)' }}>
      {/* ── Header ──────────────────────────────────────────────── */}
      <header className="sticky top-0 z-20" style={{ background: 'rgba(2,6,23,0.85)', backdropFilter: 'blur(16px)' }}>
        <div className="mx-auto flex max-w-6xl items-center gap-4 px-4 py-3">
          {/* Logo */}
          <Link
            to="/"
            id="nav-home"
            className="flex items-center gap-2 text-base font-bold tracking-tight text-slate-100 transition-opacity hover:opacity-80"
          >
            <span className="logo-icon text-xl" aria-hidden>🪢</span>
            <span>Tether</span>
          </Link>

          {/* Org pill */}
          <span className="hidden rounded-md bg-slate-800/80 px-2.5 py-1 text-xs font-medium text-slate-300 ring-1 ring-slate-700/50 sm:block">
            {tenant.name}
          </span>

          {/* Join code */}
          {tenant.joinCode && (
            <button
              id="nav-copy-join-code"
              onClick={copyJoinCode}
              title="Teammates use this code to join your organization"
              className="hidden rounded-md border border-dashed border-slate-700 px-2.5 py-1 font-mono text-xs text-slate-400 transition-all hover:border-sky-600 hover:text-sky-300 sm:block"
            >
              {copied
                ? <span className="text-emerald-400">✓ Copied!</span>
                : <>Join: <span className="tracking-wider">{tenant.joinCode}</span></>
              }
            </button>
          )}

          {/* Right side */}
          <div className="ml-auto flex items-center gap-3">
            {/* User info */}
            <div className="hidden items-center gap-2 sm:flex">
              <div
                className="flex h-7 w-7 items-center justify-center rounded-full bg-sky-600 text-xs font-bold text-white ring-2 ring-sky-900/50"
                aria-hidden
              >
                {user.displayName.split(/\s+/).map(w => w[0]).slice(0, 2).join('').toUpperCase()}
              </div>
              <div className="text-sm leading-tight">
                <div className="font-medium text-slate-200">{user.displayName}</div>
                <div className="text-xs text-slate-500">{user.role.toLowerCase()}</div>
              </div>
            </div>

            {/* Sign out */}
            <button
              id="nav-sign-out"
              onClick={() => logout()}
              className="flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm text-slate-400 transition-all hover:bg-slate-800 hover:text-slate-100"
            >
              <svg className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.75" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a2 2 0 01-2 2H5a2 2 0 01-2-2V7a2 2 0 012-2h6a2 2 0 012 2v1" />
              </svg>
              Sign out
            </button>
          </div>
        </div>
        {/* Subtle bottom glow line */}
        <div className="header-glow" />
      </header>

      {/* ── Main ────────────────────────────────────────────────── */}
      <main className="mx-auto max-w-6xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}
