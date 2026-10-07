import { useState } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext.jsx'
import { ErrorBanner, Field, buttonClass, inputClass } from '../components/Field.jsx'

export default function LoginPage() {
  const { session, notice, login, register } = useAuth()
  const [mode, setMode] = useState('signin')
  const [orgMode, setOrgMode] = useState('create')
  const [form, setForm] = useState({ email: '', password: '', displayName: '', organizationName: '', joinCode: '' })
  const [error, setError] = useState(null)
  const [fields, setFields] = useState({})
  const [busy, setBusy] = useState(false)

  if (session) return <Navigate to="/" replace />

  const set = (key) => (e) => setForm({ ...form, [key]: e.target.value })

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    setFields({})
    try {
      if (mode === 'signin') {
        await login(form.email, form.password)
      } else {
        await register({
          email: form.email,
          password: form.password,
          displayName: form.displayName,
          ...(orgMode === 'create' ? { organizationName: form.organizationName } : { joinCode: form.joinCode }),
        })
      }
    } catch (err) {
      setError(err.message)
      setFields(err.fields ?? {})
    } finally {
      setBusy(false)
    }
  }

  const tab = (value, label) => (
    <button
      type="button"
      id={`login-tab-${value}`}
      onClick={() => { setMode(value); setError(null); setFields({}) }}
      className={`flex-1 rounded-md py-2 text-sm font-semibold transition-all duration-200 ${
        mode === value
          ? 'bg-sky-600 text-white shadow-lg shadow-sky-900/30'
          : 'text-slate-400 hover:text-slate-200'
      }`}
    >
      {label}
    </button>
  )

  return (
    <div className="relative flex min-h-screen items-center justify-center px-4">
      {/* Animated background */}
      <div className="login-bg" aria-hidden />
      <div className="orb orb-1" aria-hidden />
      <div className="orb orb-2" aria-hidden />
      <div className="orb orb-3" aria-hidden />

      {/* Card */}
      <div className="relative z-10 w-full max-w-sm slide-up">
        {/* Logo */}
        <div className="mb-8 text-center">
          <div className="logo-icon mb-3 text-5xl" aria-hidden>🪢</div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-100">Tether</h1>
          <p className="mt-1.5 text-sm text-slate-400">Real-time incident response</p>
        </div>

        {/* Auth card */}
        <div className="glass rounded-2xl p-7">
          {/* Mode tabs */}
          <div className="mb-6 flex gap-1 rounded-xl bg-slate-950/70 p-1">
            {tab('signin', 'Sign in')}
            {tab('register', 'Create account')}
          </div>

          <form onSubmit={submit} className="space-y-4" id="login-form">
            {/* Notice (e.g. session expired) */}
            {notice && (
              <div className="flex items-center gap-2 rounded-lg bg-amber-950/50 px-3 py-2.5 text-sm text-amber-300 ring-1 ring-amber-900/50">
                <svg className="h-4 w-4 shrink-0" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 5a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 5zm0 9a1 1 0 100-2 1 1 0 000 2z" clipRule="evenodd" />
                </svg>
                {notice}
              </div>
            )}

            <ErrorBanner error={error} />

            {/* Display name (register only) */}
            {mode === 'register' && (
              <Field label="Your name" error={fields.displayName}>
                <input
                  id="register-displayName"
                  className={inputClass}
                  value={form.displayName}
                  onChange={set('displayName')}
                  required
                  autoComplete="name"
                  placeholder="Jane Smith"
                />
              </Field>
            )}

            {/* Email */}
            <Field label="Email" error={fields.email}>
              <input
                id="login-email"
                className={inputClass}
                type="email"
                value={form.email}
                onChange={set('email')}
                required
                autoComplete="email"
                placeholder="you@company.com"
              />
            </Field>

            {/* Password */}
            <Field label="Password" error={fields.password}>
              <input
                id="login-password"
                className={inputClass}
                type="password"
                value={form.password}
                onChange={set('password')}
                required
                minLength={mode === 'register' ? 8 : undefined}
                autoComplete={mode === 'register' ? 'new-password' : 'current-password'}
                placeholder={mode === 'register' ? 'Minimum 8 characters' : '••••••••'}
              />
            </Field>

            {/* Org options (register only) */}
            {mode === 'register' && (
              <fieldset className="space-y-3">
                <div className="flex gap-4 text-sm">
                  <label className="flex cursor-pointer items-center gap-2 text-slate-300 transition-colors hover:text-slate-100">
                    <input
                      id="org-mode-create"
                      type="radio"
                      className="accent-sky-500"
                      checked={orgMode === 'create'}
                      onChange={() => setOrgMode('create')}
                    />
                    New organization
                  </label>
                  <label className="flex cursor-pointer items-center gap-2 text-slate-300 transition-colors hover:text-slate-100">
                    <input
                      id="org-mode-join"
                      type="radio"
                      className="accent-sky-500"
                      checked={orgMode === 'join'}
                      onChange={() => setOrgMode('join')}
                    />
                    Join with code
                  </label>
                </div>

                {orgMode === 'create' ? (
                  <Field label="Organization name" error={fields.organizationName}>
                    <input
                      id="register-orgName"
                      className={inputClass}
                      value={form.organizationName}
                      onChange={set('organizationName')}
                      required
                      placeholder="Acme Corp"
                    />
                  </Field>
                ) : (
                  <Field label="Join code" error={fields.joinCode}>
                    <input
                      id="register-joinCode"
                      className={`${inputClass} font-mono uppercase tracking-widest`}
                      value={form.joinCode}
                      onChange={set('joinCode')}
                      required
                      placeholder="ABC-123"
                    />
                  </Field>
                )}
              </fieldset>
            )}

            {/* Submit */}
            <button
              id="login-submit"
              type="submit"
              disabled={busy}
              className={`${buttonClass} mt-2 w-full gap-2`}
            >
              {busy ? (
                <>
                  <svg className="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                  </svg>
                  Please wait…
                </>
              ) : mode === 'signin' ? 'Sign in' : 'Create account'}
            </button>
          </form>
        </div>

        {/* Footer */}
        <p className="mt-6 text-center text-xs text-slate-600">
          Tether — built for reliability under pressure
        </p>
      </div>
    </div>
  )
}
