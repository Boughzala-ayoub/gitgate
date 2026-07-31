import { useState } from 'react'
import { api } from './api'
import type { Me, Repo } from './types'
import { RepoIcon } from './icons'
import { cx, ErrorNote, Spinner, useAsync, VisibilityLabel } from './ui'
import CodeBrowser from './CodeBrowser'
import Collaborators from './Collaborators'
import Visibility from './Visibility'

type Tab = 'code' | 'settings'

export default function RepoDetail({ name, me, onBack }: { name: string; me: Me; onBack: () => void }) {
  const repo = useAsync<Repo>(() => api.getRepo(name), [name])
  const [tab, setTab] = useState<Tab>('code')

  if (repo.error) return <ErrorNote message={repo.error} />
  if (!repo.data) return <Spinner />

  const r = repo.data
  const canManage = r.myRole === 'owner' || r.myRole === 'admin'

  return (
    <>
      <div className="row" style={{ marginBottom: 12 }}>
        <button className="btn btn-sm" onClick={onBack}>← Repositories</button>
      </div>

      <div className="repo-title">
        <RepoIcon className="muted" size={18} />
        <span className="owner">{r.owner ?? '—'}</span>
        <span className="sep">/</span>
        <span className="name">{r.name}</span>
        <VisibilityLabel visibility={r.visibility} />
      </div>

      <nav className="UnderlineNav">
        <button className={cx('UnderlineNav-item', tab === 'code' && 'selected')} onClick={() => setTab('code')}>Code</button>
        {canManage && <button className={cx('UnderlineNav-item', tab === 'settings' && 'selected')} onClick={() => setTab('settings')}>Settings</button>}
      </nav>

      {tab === 'code' && (
        <>
          <CloneBar repo={r} me={me} />
          <CodeBrowser name={name} />
        </>
      )}

      {tab === 'settings' && canManage && (
        <>
          <Visibility repo={r} canManage onChange={repo.reload} />
          <Collaborators name={name} />
          <DangerZone name={name} onDeleted={onBack} />
        </>
      )}
    </>
  )
}

function CloneBar({ repo, me }: { repo: Repo; me: Me }) {
  // Use the address this dashboard is actually served from, not a hardcoded one.
  const host = window.location.host
  return (
    <div className="Box">
      <div className="Box-body stack" style={{ gap: 8 }}>
        <div className="row"><span className="muted" style={{ minWidth: 90 }}>Clone (you)</span>
          <code className="codeline grow">git clone http://{me.username}@{host}/a/{repo.name}.git</code></div>
        {repo.visibility === 'PUBLIC' && (
          <>
            <div className="row"><span className="muted" style={{ minWidth: 90 }}>Anonymous</span>
              <code className="codeline grow">git clone http://{host}/{repo.name}.git</code></div>
            <p className="field-hint" style={{ margin: 0 }}>
              The <code>/a/</code> path asks for your credentials, so you get <em>your</em> view of
              this repository. Without it git is never challenged on a public repo and you would
              silently receive the public view instead.
            </p>
          </>
        )}
      </div>
    </div>
  )
}

function DangerZone({ name, onDeleted }: { name: string; onDeleted: () => void }) {
  const [error, setError] = useState('')
  async function del() {
    if (!confirm(`Delete repository "${name}"? This permanently removes it.`)) return
    try { await api.deleteRepo(name); onDeleted() }
    catch (e) { setError(e instanceof Error ? e.message : 'Failed') }
  }
  return (
    <div className="Box Box-danger">
      <div className="Box-header"><h2 className="Box-title">Danger zone</h2></div>
      <div className="Box-body row between">
        <div><b>Delete this repository</b><div className="muted">Removes the repo and all its access rules.</div></div>
        <button className="btn btn-danger" onClick={del}>Delete</button>
      </div>
      {error && <div className="Box-body"><ErrorNote message={error} /></div>}
    </div>
  )
}
