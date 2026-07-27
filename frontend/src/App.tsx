import { useEffect, useState } from 'react'
import { api, clearAuth, hasAuth, setAuth } from './api'
import type { Me } from './types'
import { Mark, MenuIcon, SignOutIcon, XIcon } from './icons'
import { cx, ErrorNote, Spinner } from './ui'
import RepoList from './RepoList'
import RepoDetail from './RepoDetail'
import AdminPanel from './AdminPanel'
import Explore from './Explore'

type Section = 'repos' | 'explore' | 'admin'

export default function App() {
  const [me, setMe] = useState<Me | null>(null)
  const [checking, setChecking] = useState(true)
  const [section, setSection] = useState<Section>('repos')
  const [openRepo, setOpenRepo] = useState<string | null>(null)
  const [menuOpen, setMenuOpen] = useState(false)

  useEffect(() => {
    if (!hasAuth()) { setChecking(false); return }
    api.me().then(setMe).catch(() => clearAuth()).finally(() => setChecking(false))
  }, [])

  function signOut() {
    clearAuth(); setMe(null); setOpenRepo(null); setSection('repos'); setMenuOpen(false)
  }

  if (checking) return <div className="container page"><Spinner /></div>
  if (!me) return <AuthScreen onAuthed={setMe} />

  function go(s: Section) { setSection(s); setOpenRepo(null); setMenuOpen(false) }

  return (
    <>
      <header className="AppHeader">
        <div className="AppHeader-inner">
          <button className="brand btn-invisible" onClick={() => go('repos')} style={{ padding: '4px 6px' }}>
            <Mark /> GitGate
          </button>

          <button className="hdr-toggle btn btn-invisible btn-sm" aria-label="Toggle navigation menu"
                  aria-expanded={menuOpen} onClick={() => setMenuOpen((v) => !v)} style={{ color: '#fff' }}>
            {menuOpen ? <XIcon size={18} /> : <MenuIcon size={18} />}
          </button>

          <div className={cx('hdr-menu', menuOpen && 'open')}>
            <nav className="hdr-nav">
              <button className={cx('btn btn-invisible btn-sm', section === 'repos' && 'active')} onClick={() => go('repos')} style={{ color: '#fff' }}>
                Repositories
              </button>
              <button className={cx('btn btn-invisible btn-sm', section === 'explore' && 'active')} onClick={() => go('explore')} style={{ color: '#fff' }}>
                Explore
              </button>
              {me.superadmin && (
                <button className={cx('btn btn-invisible btn-sm', section === 'admin' && 'active')} onClick={() => go('admin')} style={{ color: '#fff' }}>
                  Admin
                </button>
              )}
            </nav>
            <div className="hdr-user">
              <span className="who">{me.username}{me.superadmin ? ' · admin' : ''}</span>
              <button className="btn btn-invisible btn-sm" onClick={signOut} style={{ color: '#fff' }}>
                <SignOutIcon size={14} /> <span className="signout-label">Sign out</span>
              </button>
            </div>
          </div>
        </div>
      </header>

      <main className="container page">
        {openRepo ? (
          <RepoDetail name={openRepo} me={me} onBack={() => setOpenRepo(null)} />
        ) : section === 'repos' ? (
          <RepoList onOpen={setOpenRepo} />
        ) : section === 'explore' ? (
          <Explore onOpen={setOpenRepo} />
        ) : (
          <AdminPanel />
        )}
      </main>
    </>
  )
}

function AuthScreen({ onAuthed }: { onAuthed: (me: Me) => void }) {
  const [mode, setMode] = useState<'login' | 'signup'>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError(''); setBusy(true)
    try {
      if (mode === 'signup') await api.signup(username, password)
      setAuth(username, password)
      onAuthed(await api.me())
    } catch (err) {
      clearAuth()
      setError(mode === 'signup' ? (err instanceof Error ? err.message : 'Sign up failed') : 'Incorrect username or password.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-wrap">
      <div className="auth-mark"><Mark size={48} /><div style={{ fontWeight: 300, fontSize: 24, marginTop: 8 }}>Sign in to GitGate</div></div>
      <form className="auth-card" onSubmit={submit}>
        <div className="field">
          <label className="field-label" htmlFor="u">Username</label>
          <input id="u" className="form-control" value={username} onChange={(e) => setUsername(e.target.value)} autoFocus autoComplete="username" />
        </div>
        <div className="field">
          <label className="field-label" htmlFor="p">Password</label>
          <input id="p" type="password" className="form-control" value={password} onChange={(e) => setPassword(e.target.value)}
                 autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                 placeholder={mode === 'login' ? 'e.g. admin123' : 'at least 4 characters'} />
        </div>
        {error && <ErrorNote message={error} />}
        <button className="btn btn-primary" style={{ width: '100%', justifyContent: 'center' }} type="submit" disabled={busy}>
          {busy ? <Spinner /> : mode === 'login' ? 'Sign in' : 'Create account'}
        </button>
      </form>
      <div className="auth-switch">
        {mode === 'login' ? 'New to GitGate?' : 'Already have an account?'}{' '}
        <button className="btn-link" onClick={() => { setMode(mode === 'login' ? 'signup' : 'login'); setError('') }}>
          {mode === 'login' ? 'Create an account' : 'Sign in'}
        </button>
      </div>
    </div>
  )
}
