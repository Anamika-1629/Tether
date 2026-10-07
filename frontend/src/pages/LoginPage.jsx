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
      onClick={() => { setMode(value); setError(null); setFields({}) }}
      className={`flex-1 rounded-md py-2 text-sm font-medium ${mode === value ? 'bg-slate-800 text-white' : 'text-slate-400 hover:text-slate-200'}`}
    >
      {label}
    </button>
  )

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-sm">
        <div className="mb-8 text-center">
          <div className="text-4xl" aria-hidden>🪢</div>
          <h1 className="mt-2 text-2xl font-bold tracking-tight">Tether</h1>
          <p className="mt-1 text-sm text-slate-400">Real-time incident response</p>
        </div>

        <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-6 shadow-xl">
          <div className="mb-6 flex gap-1 rounded-lg bg-slate-950 p-1">
            {tab('signin', 'Sign in')}
            {tab('register', 'Create account')}
          </div>

          <form onSubmit={submit} className="space-y-4">
            {notice && <div className="rounded-md bg-amber-950/60 px-3 py-2 text-sm text-amber-300">{notice}</div>}
            <ErrorBanner error={error} />

            {mode === 'register' && (
              <Field label="Your name" error={fields.displayName}>
                <input className={inputClass} value={form.displayName} onChange={set('displayName')} required autoComplete="name" />
              </Field>
            )}
            <Field label="Email" error={fields.email}>
              <input className={inputClass} type="email" value={form.email} onChange={set('email')} required autoComplete="email" />
            </Field>
            <Field label="Password" error={fields.password}>
              <input
                className={inputClass}
                type="password"
                value={form.password}
                onChange={set('password')}
                required
                minLength={mode === 'register' ? 8 : undefined}
                autoComplete={mode === 'register' ? 'new-password' : 'current-password'}
              />
            </Field>

            {mode === 'register' && (
              <fieldset className="space-y-3">
                <div className="flex gap-4 text-sm">
                  <label className="flex items-center gap-2">
                    <input type="radio" checked={orgMode === 'create'} onChange={() => setOrgMode('create')} /> New organization
                  </label>
                  <label className="flex items-center gap-2">
                    <input type="radio" checked={orgMode === 'join'} onChange={() => setOrgMode('join')} /> Join with code
                  </label>
                </div>
                {orgMode === 'create' ? (
                  <Field label="Organization name" error={fields.organizationName}>
                    <input className={inputClass} value={form.organizationName} onChange={set('organizationName')} required placeholder="Acme Corp" />
                  </Field>
                ) : (
                  <Field label="Join code" error={fields.joinCode}>
                    <input className={`${inputClass} font-mono uppercase`} value={form.joinCode} onChange={set('joinCode')} required placeholder="From your organization owner" />
                  </Field>
                )}
              </fieldset>
            )}

            <button type="submit" disabled={busy} className={`${buttonClass} w-full`}>
              {busy ? 'Please wait…' : mode === 'signin' ? 'Sign in' : 'Create account'}
            </button>
          </form>
        </div>
      </div>
    </div>
  )
}
