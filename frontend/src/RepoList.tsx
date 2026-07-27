import { useState } from 'react'
import { api } from './api'
import { PlusIcon, RepoIcon } from './icons'
import { ErrorNote, Spinner, useAsync, VisibilityLabel } from './ui'
import type { Repo } from './types'

export default function RepoList({ onOpen }: { onOpen: (name: string) => void }) {
  const repos = useAsync<Repo[]>(api.myRepos)
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [error, setError] = useState('')

  async function create(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    try { await api.createRepo(name); setName(''); setCreating(false); repos.reload() }
    catch (err) { setError(err instanceof Error ? err.message : 'Failed') }
  }

  return (
    <>
      <div className="page-head">
        <h1>Repositories</h1>
        <button className="btn btn-primary" onClick={() => setCreating((v) => !v)}>
          <PlusIcon size={14} /> New
        </button>
      </div>

      {creating && (
        <form className="Box" onSubmit={create}>
          <div className="Box-body">
            <div className="field">
              <label className="field-label" htmlFor="repo-name">Repository name</label>
              <input id="repo-name" className="form-control input-inline" placeholder="my-project"
                     value={name} onChange={(e) => setName(e.target.value)} autoFocus />
            </div>
            {error && <ErrorNote message={error} />}
            <div className="row">
              <button className="btn btn-primary" disabled={!name} type="submit">Create repository</button>
              <button className="btn" type="button" onClick={() => { setCreating(false); setError('') }}>Cancel</button>
            </div>
          </div>
        </form>
      )}

      {repos.error && <ErrorNote message={repos.error} />}
      {!repos.data ? <Spinner /> : (
        <div className="Box">
          {repos.data.length === 0 && <div className="blankslate"><RepoIcon size={24} /><p>You don't have any repositories yet. Create one, or push to a new name.</p></div>}
          {repos.data.map((r) => (
            <div className="Box-row repo-row" key={r.name}>
              <div className="row">
                <RepoIcon className="muted" />
                <button className="btn-link reponame" onClick={() => onOpen(r.name)}>{r.name}</button>
                <VisibilityLabel visibility={r.visibility} />
              </div>
              <div className="meta">
                <span>owner <span className="mono">{r.owner ?? '—'}</span></span>
                <span>your role: <span className="mono">{r.myRole}</span></span>
              </div>
            </div>
          ))}
        </div>
      )}
    </>
  )
}
