import { useState } from 'react'
import { api } from './api'
import { CheckIcon, GlobeIcon, LockIcon } from './icons'
import { cx, ErrorNote } from './ui'
import PathPicker from './PathPicker'
import type { Repo, Scope, Visibility as Vis } from './types'

/**
 * What the server would store for a given selection. Comparing signatures is how the panel knows
 * whether there is anything to save — without it the "Saved" tag stuck around while the user
 * carried on editing, cheerfully claiming that unsaved changes were saved.
 */
function signature(visibility: Vis, scopes: Scope[]): string {
  if (visibility === 'PRIVATE') return 'PRIVATE'
  const paths = scopes.filter((s) => !s.excluded).map((s) => s.path).sort().join('\n')
  const hidden = scopes.filter((s) => s.excluded).map((s) => s.path).sort().join('\n')
  return `PUBLIC|${paths}|${hidden}`
}

function scopesOf(repo: Repo): Scope[] {
  return [
    ...repo.publicPaths.map((p) => ({ path: p, read: true, write: false })),
    ...(repo.publicExclusions ?? []).map((p) => ({ path: p, read: false, write: false, excluded: true })),
  ]
}

export default function Visibility({ repo, canManage, onChange }: { repo: Repo; canManage: boolean; onChange: () => void }) {
  const [visibility, setVisibility] = useState<Vis>(repo.visibility)
  const [scopes, setScopes] = useState<Scope[]>(() => scopesOf(repo))
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  // Set once a save lands, so the tag survives the parent's reload without a flicker.
  const [savedSignature, setSavedSignature] = useState<string | null>(null)

  const baseline = savedSignature ?? signature(repo.visibility, scopesOf(repo))
  const dirty = signature(visibility, scopes) !== baseline
  const justSaved = savedSignature !== null && !dirty

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
                {(repo.publicExclusions ?? []).length > 0 && (
                  <>
                    <span className="muted">except, which never leave the server:</span>
                    <div className="chips">
                      {repo.publicExclusions.map((p) => (
                        <span className="chip chip-excluded" key={p}><code>{p}</code></span>
                      ))}
                    </div>
                  </>
                )}
              </div>
            )
            : <span className="muted"><LockIcon size={14} /> Private</span>}
        </div>
      </section>
    )
  }

  async function save() {
    setError(''); setSaving(true)
    const live = visibility === 'PUBLIC' ? scopes : []
    const publicPaths = live.filter((s) => !s.excluded).map((s) => s.path)
    const publicExclusions = live.filter((s) => s.excluded).map((s) => s.path)
    try {
      await api.setVisibility(repo.name, { visibility, publicPaths, publicExclusions })
      setSavedSignature(signature(visibility, scopes))
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
        <div className="choice-cards" role="radiogroup" aria-label="Repository visibility">
          <button className={cx('choice-card', visibility === 'PRIVATE' && 'selected')}
                  role="radio" aria-checked={visibility === 'PRIVATE'}
                  onClick={() => setVisibility('PRIVATE')}>
            <span className="choice-card-title"><LockIcon size={14} /> Private</span>
            <span className="choice-card-hint">Only you and your collaborators.</span>
            <span className="choice-card-mark" aria-hidden="true"><CheckIcon size={12} /></span>
          </button>
          <button className={cx('choice-card', visibility === 'PUBLIC' && 'selected')}
                  role="radio" aria-checked={visibility === 'PUBLIC'}
                  onClick={() => setVisibility('PUBLIC')}>
            <span className="choice-card-title"><GlobeIcon size={14} /> Public</span>
            <span className="choice-card-hint">Anyone can read the paths you mark public.</span>
            <span className="choice-card-mark" aria-hidden="true"><CheckIcon size={12} /></span>
          </button>
        </div>

        {visibility === 'PUBLIC' && (
          <div className="field">
            <span className="field-label">What should the public see?</span>
            <p className="field-hint">
              By default the whole repository is public — use <b>Exclude</b> to keep individual
              files or folders out of anonymous clones. Or mark specific paths public to expose
              only those. Anonymous access is always read-only, and an exclusion always wins.
            </p>
            <PathPicker repo={repo.name} mode="read" value={scopes} onChange={setScopes} />
          </div>
        )}

        {error && <ErrorNote message={error} />}
        <div className="form-actions">
          <button className="btn btn-primary" onClick={save} disabled={saving || !dirty}>
            {saving ? 'Saving…' : 'Save visibility'}
          </button>
          <span className="form-actions-status" role="status">
            {dirty && !saving && <span className="status-dirty">Unsaved changes</span>}
            {justSaved && !saving && <span className="status-saved"><CheckIcon size={13} /> Saved</span>}
          </span>
        </div>
      </div>
    </section>
  )
}
