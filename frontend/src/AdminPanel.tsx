import { useState } from 'react'
import { api } from './api'
import { ErrorNote, Spinner, useAsync } from './ui'
import type { AdminUser, AuditEntry } from './types'

export default function AdminPanel() {
  const users = useAsync<AdminUser[]>(api.adminUsers)
  const audit = useAsync<AuditEntry[]>(api.adminAudit)
  const [secret, setSecret] = useState<string | null>(null)
  const [error, setError] = useState('')

  async function mint(id: number) {
    const label = prompt('Token label:', 'token')
    if (label === null) return
    try { const r = await api.adminMintToken(id, label); setSecret(r.token); users.reload() }
    catch (e) { setError(e instanceof Error ? e.message : 'Failed') }
  }

  return (
    <>
      <div className="page-head"><h1>Admin</h1></div>

      {secret && (
        <div className="Box">
          <div className="Box-header"><h2 className="Box-title">New token — copy now, shown once</h2></div>
          <div className="Box-body">
            <div className="secret">{secret}</div>
            <div style={{ marginTop: 10 }}><button className="btn" onClick={() => setSecret(null)}>Dismiss</button></div>
          </div>
        </div>
      )}

      <div className="Box">
        <div className="Box-header"><h2 className="Box-title">All users</h2></div>
        {error && <div className="Box-body"><ErrorNote message={error} /></div>}
        {!users.data ? <div className="Box-body"><Spinner /></div> : (
          <table className="list">
            <thead><tr><th>User</th><th>Roles</th><th>Tokens</th><th /></tr></thead>
            <tbody>
              {users.data.map((u) => (
                <tr key={u.id}>
                  <td className="mono" data-label="User">{u.username}</td>
                  <td data-label="Roles">{u.roles.join(', ') || '—'}</td>
                  <td data-label="Tokens">{u.tokenCount}</td>
                  <td><button className="btn btn-sm" onClick={() => mint(u.id)}>Mint token</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="Box">
        <div className="Box-header between"><h2 className="Box-title">Audit log</h2><button className="btn btn-sm" onClick={audit.reload}>Refresh</button></div>
        {!audit.data ? <div className="Box-body"><Spinner /></div> : (
          <table className="list">
            <thead><tr><th>Time</th><th>User</th><th>Repo</th><th>Action</th></tr></thead>
            <tbody>
              {audit.data.map((e, i) => (
                <tr key={i}>
                  <td className="muted mono" data-label="Time">{new Date(e.ts).toLocaleString()}</td>
                  <td className="mono" data-label="User">{e.username}</td>
                  <td className="mono" data-label="Repo">{e.repo}</td>
                  <td data-label="Action">{e.action}</td>
                </tr>
              ))}
              {audit.data.length === 0 && <tr><td colSpan={4} className="muted">No events yet.</td></tr>}
            </tbody>
          </table>
        )}
      </div>
    </>
  )
}
