import { useState } from 'react'
import { api } from './api'
import { ErrorNote, Spinner, useAsync } from './ui'
import type { Collaborator, Scope } from './types'

export default function Collaborators({ name }: { name: string }) {
  const collabs = useAsync<Collaborator[]>(() => api.collaborators(name), [name])
  const [error, setError] = useState('')

  async function remove(username: string) {
    setError('')
    try { await api.removeCollaborator(name, username); collabs.reload() }
    catch (e) { setError(e instanceof Error ? e.message : 'Failed') }
  }

  return (
    <div className="Box">
      <div className="Box-header"><h2 className="Box-title">Collaborators</h2></div>
      {collabs.error && <div className="Box-body"><ErrorNote message={collabs.error} /></div>}
      {!collabs.data ? <div className="Box-body"><Spinner /></div> : (
        <table className="list">
          <thead><tr><th>User</th><th>Role</th><th>Path scopes</th><th /></tr></thead>
          <tbody>
            {collabs.data.map((c) => (
              <tr key={c.username}>
                <td className="mono" data-label="User">{c.username}</td>
                <td data-label="Role"><span className="Label role">{c.role.toLowerCase()}</span></td>
                <td data-label="Path scopes">
                  {c.scopes.length === 0 ? <span className="muted">whole repo</span> : c.scopes.map((s, i) => (
                    <div key={i} className="mono">
                      {s.path} {s.read && <span className="tag-r">read</span>} {s.write && <span className="tag-w">write</span>}
                    </div>
                  ))}
                </td>
                <td><button className="btn btn-danger btn-sm" onClick={() => remove(c.username)}>Remove</button></td>
              </tr>
            ))}
            {collabs.data.length === 0 && <tr><td colSpan={4} className="muted">No collaborators yet.</td></tr>}
          </tbody>
        </table>
      )}
      <div className="Box-body" style={{ borderTop: '1px solid var(--border-muted)' }}>
        {error && <ErrorNote message={error} />}
        <AddCollaborator name={name} onAdded={collabs.reload} />
      </div>
    </div>
  )
}

function AddCollaborator({ name, onAdded }: { name: string; onAdded: () => void }) {
  const [username, setUsername] = useState('')
  const [role, setRole] = useState('WRITE')
  const [scopes, setScopes] = useState<Scope[]>([{ path: '', read: true, write: false }])
  const [error, setError] = useState('')

  function patch(i: number, p: Partial<Scope>) {
    setScopes(scopes.map((s, idx) => (idx === i ? { ...s, ...p } : s)))
  }

  async function add() {
    setError('')
    try {
      await api.addCollaborator(name, { username, role, scopes: scopes.filter((s) => s.path.trim()) })
      setUsername(''); setScopes([{ path: '', read: true, write: false }]); onAdded()
    } catch (e) { setError(e instanceof Error ? e.message : 'Failed') }
  }

  return (
    <div>
      <label className="field-label">Add a collaborator</label>
      <div className="row" style={{ marginBottom: 10 }}>
        <input className="form-control input-inline" placeholder="username" value={username} onChange={(e) => setUsername(e.target.value)} />
        <select className="form-control input-inline" style={{ minWidth: 120 }} value={role} onChange={(e) => setRole(e.target.value)}>
          <option>READ</option><option>WRITE</option><option>ADMIN</option>
        </select>
      </div>
      <div className="muted" style={{ marginBottom: 6 }}>Path scopes — leave empty for whole-repo access:</div>
      {scopes.map((s, i) => (
        <div className="row" key={i} style={{ marginBottom: 6 }}>
          <input className="form-control input-inline" placeholder="e.g. frontend or docs/**" value={s.path} onChange={(e) => patch(i, { path: e.target.value })} />
          <label className="row"><input type="checkbox" checked={s.read} onChange={(e) => patch(i, { read: e.target.checked })} /> read</label>
          <label className="row"><input type="checkbox" checked={s.write} onChange={(e) => patch(i, { write: e.target.checked })} /> write</label>
          {scopes.length > 1 && <button className="btn btn-danger btn-sm" onClick={() => setScopes(scopes.filter((_, idx) => idx !== i))}>×</button>}
        </div>
      ))}
      <div className="row" style={{ marginTop: 8 }}>
        <button className="btn btn-sm" onClick={() => setScopes([...scopes, { path: '', read: true, write: false }])}>Add scope</button>
        <button className="btn btn-primary" disabled={!username} onClick={add}>Add collaborator</button>
      </div>
      {error && <ErrorNote message={error} />}
    </div>
  )
}
