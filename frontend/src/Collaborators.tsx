import { useState } from 'react'
import { api } from './api'
import { CheckIcon, EyeClosedIcon, FileIcon, PeopleIcon, PlusIcon, RepoIcon, XIcon } from './icons'
import { cx, ErrorNote, Spinner, useAsync } from './ui'
import PathPicker from './PathPicker'
import type { Collaborator, Scope } from './types'

const ROLES = [
  { key: 'READ', text: 'Read', hint: 'Clone and browse' },
  { key: 'WRITE', text: 'Write', hint: 'Clone and push' },
  { key: 'ADMIN', text: 'Admin', hint: 'Manage collaborators' },
]

export default function Collaborators({ name }: { name: string }) {
  const collabs = useAsync<Collaborator[]>(() => api.collaborators(name), [name])
  const [editing, setEditing] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const [error, setError] = useState('')

  async function remove(username: string) {
    if (!confirm(`Remove ${username} from this repository?`)) return
    setError('')
    try { await api.removeCollaborator(name, username); collabs.reload() }
    catch (e) { setError(e instanceof Error ? e.message : 'Failed') }
  }

  return (
    <section className="Box">
      <div className="Box-header between">
        <div>
          <h2 className="Box-title">People with access</h2>
          <p className="Box-subtitle">Grant access to the whole repository, or only to certain files and folders.</p>
        </div>
        <button className="btn btn-primary btn-sm" onClick={() => { setAdding(true); setEditing(null) }}>
          <PlusIcon size={14} /> Add people
        </button>
      </div>

      {error && <div className="Box-body"><ErrorNote message={error} /></div>}
      {collabs.error && <div className="Box-body"><ErrorNote message={collabs.error} /></div>}

      {adding && (
        <div className="Box-body panel">
          <AccessEditor
            repo={name}
            onCancel={() => setAdding(false)}
            onSaved={() => { setAdding(false); collabs.reload() }}
          />
        </div>
      )}

      {!collabs.data ? <div className="Box-body"><Spinner /></div> : (
        <>
          {collabs.data.length === 0 && !adding && (
            <div className="blankslate">
              <PeopleIcon size={24} className="blankslate-icon" />
              <p className="blankslate-title">No collaborators yet</p>
              <p className="blankslate-hint">
                Only you can see this repository. Add someone and choose exactly which files
                they can read or change.
              </p>
              <button className="btn btn-primary" onClick={() => setAdding(true)}>
                <PlusIcon size={14} /> Add your first collaborator
              </button>
            </div>
          )}
          {collabs.data.map((c) => (
            <div key={c.username}>
              <div className={cx('person', editing === c.username && 'is-editing')}>
                <span className="avatar" aria-hidden="true">{c.username.slice(0, 2).toUpperCase()}</span>
                <div className="person-main">
                  <div className="person-name">{c.username}</div>
                  <div className="person-scopes">
                    {c.scopes.length === 0
                      ? <span className="muted">Access to the entire repository</span>
                      : c.scopes.map((s) => (
                        <span className={cx('chip', s.excluded && 'chip-excluded')} key={s.path}>
                          {s.excluded && <EyeClosedIcon size={11} />}
                          <code>{s.path}</code>
                          {s.excluded
                            ? <span className="chip-access hidden">hidden</span>
                            : <span className={cx('chip-access', s.write && 'write')}>{s.write ? 'read + write' : 'read'}</span>}
                        </span>
                      ))}
                  </div>
                </div>
                <span className="Label role">{c.role.toLowerCase()}</span>
                <div className="person-actions">
                  <button className="btn btn-sm" onClick={() => { setEditing(editing === c.username ? null : c.username); setAdding(false) }}>
                    {editing === c.username ? 'Close' : 'Manage access'}
                  </button>
                  <button className="btn btn-danger btn-sm" aria-label={`Remove ${c.username}`} onClick={() => remove(c.username)}>
                    <XIcon size={14} />
                  </button>
                </div>
              </div>
              {editing === c.username && (
                <div className="Box-body panel">
                  <AccessEditor
                    repo={name}
                    existing={c}
                    onCancel={() => setEditing(null)}
                    onSaved={() => { setEditing(null); collabs.reload() }}
                  />
                </div>
              )}
            </div>
          ))}
        </>
      )}
    </section>
  )
}

/**
 * Add a collaborator or change an existing one's access. The server upserts by (repo, user), so
 * both cases are the same request — editing is just adding with the username locked.
 */
function AccessEditor({
  repo, existing, onCancel, onSaved,
}: {
  repo: string
  existing?: Collaborator
  onCancel: () => void
  onSaved: () => void
}) {
  const [username, setUsername] = useState(existing?.username ?? '')
  const [role, setRole] = useState(existing?.role ?? 'WRITE')
  const [scopes, setScopes] = useState<Scope[]>(existing?.scopes ?? [])
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  // A membership with no grants means the whole repository on the server, so that's how an
  // existing one round-trips back into the editor.
  const [wholeRepo, setWholeRepo] = useState(() => !(existing?.scopes ?? []).some((s) => !s.excluded))

  const droppedGrants = scopes.filter((s) => !s.excluded).length

  /** Switching to whole-repo drops the grants (exclusions survive) — visibly, not silently. */
  function chooseWholeRepo() {
    setScopes(scopes.filter((s) => s.excluded))
    setWholeRepo(true)
  }

  async function save() {
    setError(''); setSaving(true)
    try {
      await api.addCollaborator(repo, { username: username.trim(), role, scopes })
      onSaved()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="stack" style={{ gap: 16 }}>
      {!existing && (
        <div className="field">
          <label className="field-label" htmlFor="collab-user">Username</label>
          <input
            id="collab-user" className="form-control" placeholder="who are you giving access to?"
            value={username} onChange={(e) => setUsername(e.target.value)} autoFocus
          />
        </div>
      )}

      <div className="field">
        <span className="field-label">Role</span>
        <div className="role-options">
          {ROLES.map((r) => (
            <button
              key={r.key}
              className={cx('role-option', role === r.key && 'selected')}
              aria-pressed={role === r.key}
              onClick={() => setRole(r.key)}
            >
              <span className="role-option-name">{r.text}</span>
              <span className="role-option-hint">{r.hint}</span>
            </button>
          ))}
        </div>
      </div>

      <div className="field">
        <span className="field-label">Which files can they see?</span>
        <div className="choice-cards" role="radiogroup" aria-label="Which files can they see?">
          <button className={cx('choice-card', wholeRepo && 'selected')}
                  role="radio" aria-checked={wholeRepo} onClick={chooseWholeRepo}>
            <span className="choice-card-title"><RepoIcon size={14} /> Entire repository</span>
            <span className="choice-card-hint">
              Everything, then hide the odd file or folder.
              {wholeRepo || droppedGrants === 0 ? '' : ` Drops ${droppedGrants} selected path${droppedGrants > 1 ? 's' : ''}.`}
            </span>
            <span className="choice-card-mark" aria-hidden="true"><CheckIcon size={12} /></span>
          </button>
          <button className={cx('choice-card', !wholeRepo && 'selected')}
                  role="radio" aria-checked={!wholeRepo} onClick={() => setWholeRepo(false)}>
            <span className="choice-card-title"><FileIcon size={14} /> Specific paths</span>
            <span className="choice-card-hint">Nothing by default — pick what they may read or change.</span>
            <span className="choice-card-mark" aria-hidden="true"><CheckIcon size={12} /></span>
          </button>
        </div>
        <p className="field-hint" style={{ marginTop: 10 }}>
          {wholeRepo
            ? <>They get the whole repository at the access level set by <b>Role</b> above — use{' '}
              <b>Exclude</b> on any row to keep that file or folder out of their clone. An exclusion
              always wins, so it can't be undone by a broader rule.</>
            : <>Selecting a folder includes everything inside it, and <b>Read + write</b> grants
              both. Drill into a granted folder and use <b>Exclude</b> to carve individual files
              back out.</>}
        </p>
        <PathPicker repo={repo} mode="rw" value={scopes} onChange={setScopes} wholeRepo={wholeRepo} />
      </div>

      {error && <ErrorNote message={error} />}
      <div className="row">
        <button className="btn btn-primary" disabled={!username.trim() || saving} onClick={save}>
          {saving ? 'Saving…' : existing ? 'Update access' : 'Add collaborator'}
        </button>
        <button className="btn" onClick={onCancel}>Cancel</button>
      </div>
    </div>
  )
}
