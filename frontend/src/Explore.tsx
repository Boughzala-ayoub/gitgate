import { useState } from 'react'
import { api } from './api'
import { RepoIcon } from './icons'
import { ErrorNote, Spinner, useAsync, VisibilityLabel } from './ui'
import type { Repo } from './types'

export default function Explore({ onOpen }: { onOpen: (name: string) => void }) {
  const [term, setTerm] = useState('')
  const [query, setQuery] = useState('')
  const results = useAsync<Repo[]>(() => api.explore(query), [query])

  function search(e: React.FormEvent) {
    e.preventDefault()
    setQuery(term)
  }

  return (
    <>
      <div className="page-head"><h1>Explore public repositories</h1></div>

      <form className="row" style={{ marginBottom: 16 }} onSubmit={search}>
        <input className="form-control search-input" placeholder="Search public repositories…"
               value={term} onChange={(e) => setTerm(e.target.value)} autoFocus />
        <button className="btn" type="submit">Search</button>
        {query && <button className="btn btn-link" type="button" onClick={() => { setTerm(''); setQuery('') }}>Clear</button>}
      </form>

      {results.error && <ErrorNote message={results.error} />}
      {!results.data ? <Spinner /> : (
        <div className="Box">
          {results.data.length === 0
            ? <div className="blankslate"><RepoIcon size={24} /><p>No public repositories {query ? `matching “${query}”` : 'yet'}.</p></div>
            : results.data.map((r) => (
              <div className="Box-row" key={r.name} style={{ display: 'block' }}>
                <div className="row">
                  <RepoIcon className="muted" />
                  <button className="btn-link reponame" onClick={() => onOpen(r.name)}>
                    <span className="muted">{r.owner ?? '—'}/</span>{r.name}
                  </button>
                  <VisibilityLabel visibility={r.visibility} />
                </div>
                {r.publicPaths.length > 0 && (
                  <div className="meta">
                    public paths: {r.publicPaths.map((p) => <span key={p} className="mono" style={{ marginRight: 8 }}>{p}</span>)}
                  </div>
                )}
              </div>
            ))}
        </div>
      )}
    </>
  )
}
