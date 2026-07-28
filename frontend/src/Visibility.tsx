import { useState } from 'react'
import { api } from './api'
import { GlobeIcon, LockIcon } from './icons'
import { cx, ErrorNote } from './ui'
import PathPicker from './PathPicker'
import type { Repo, Scope, Visibility as Vis } from './types'

export default function Visibility({ repo, canManage, onChange }: { repo: Repo; canManage: boolean; onChange: () => void }) {
  const [visibility, setVisibility] = useState<Vis>(repo.visibility)
  const [scopes, setScopes] = useState<Scope[]>(repo.publicPaths.map((p) => ({ path: p, read: true, write: false })))
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  const [saving, setSaving] = useState(false)

  if (!canManage) {
    return (
      <section className="Box">
        <div className="Box-header"><h2 className="Box-title">Visibility</h2></div>
        <div className="Box-body">
          {repo.visibility === 'PUBLIC'
            ? (
              <div className="stack" style={{ gap: 8 }}>
                <span><GlobeIcon size={14} /> Public — anonymous visitors can read {repo.publicPaths.length ? 'these paths:' : <b>everything</b>}</span>
                {repo.publicPaths.length > 0 && (
                  <div className="chips">{repo.publicPaths.map((p) => <span className="chip" key={p}><code>{p}</code></span>)}</div>
                )}
              </div>
            )
            : <span className="muted"><LockIcon size={14} /> Private</span>}
        </div>
      </section>
    )
  }

  async function save() {
    setError(''); setSaved(false); setSaving(true)
    const publicPaths = visibility === 'PUBLIC' ? scopes.map((s) => s.path) : []
    try {
      await api.setVisibility(repo.name, { visibility, publicPaths })
      setSaved(true)
      onChange()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed')
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="Box">
      <div className="Box-header">
        <div>
          <h2 className="Box-title">Visibility</h2>
          <p className="Box-subtitle">A public repository can expose only the parts you choose — the rest stays hidden.</p>
        </div>
      </div>
      <div className="Box-body stack" style={{ gap: 16 }}>
        <div className="choice-cards">
          <button className={cx('choice-card', visibility === 'PRIVATE' && 'selected')}
                  aria-pressed={visibility === 'PRIVATE'} onClick={() => setVisibility('PRIVATE')}>
            <span className="choice-card-title"><LockIcon size={14} /> Private</span>
            <span className="choice-card-hint">Only you and your collaborators.</span>
          </button>
          <button className={cx('choice-card', visibility === 'PUBLIC' && 'selected')}
                  aria-pressed={visibility === 'PUBLIC'} onClick={() => setVisibility('PUBLIC')}>
            <span className="choice-card-title"><GlobeIcon size={14} /> Public</span>
            <span className="choice-card-hint">Anyone can read the paths you mark public.</span>
          </button>
        </div>

        {visibility === 'PUBLIC' && (
          <div className="field">
            <span className="field-label">What should the public see?</span>
            <p className="field-hint">
              Mark nothing to publish the entire repository. Anonymous access is always read-only.
            </p>
            <PathPicker repo={repo.name} mode="read" value={scopes} onChange={setScopes} />
          </div>
        )}

        {error && <ErrorNote message={error} />}
        <div className="row">
          <button className="btn btn-primary" onClick={save} disabled={saving}>
            {saving ? 'Saving…' : 'Save visibility'}
          </button>
          {saved && <span className="tag-r">Saved</span>}
        </div>
      </div>
    </section>
  )
}
