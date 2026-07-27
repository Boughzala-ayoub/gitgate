import { useState } from 'react'
import { api } from './api'
import { ErrorNote } from './ui'
import type { Repo, Visibility as Vis } from './types'

export default function Visibility({ repo, canManage, onChange }: { repo: Repo; canManage: boolean; onChange: () => void }) {
  const [visibility, setVisibility] = useState<Vis>(repo.visibility)
  const [paths, setPaths] = useState<string[]>(repo.publicPaths.length ? repo.publicPaths : [''])
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)

  if (!canManage) {
    return (
      <div className="Box">
        <div className="Box-header"><h2 className="Box-title">Visibility</h2></div>
        <div className="Box-body">
          {repo.visibility === 'PUBLIC'
            ? <span>Public — anonymous can read {repo.publicPaths.length
                ? repo.publicPaths.map((p) => <span key={p} className="Label mono" style={{ marginRight: 4 }}>{p}</span>)
                : <b>everything</b>}</span>
            : <span className="muted">Private</span>}
        </div>
      </div>
    )
  }

  async function save() {
    setError(''); setSaved(false)
    const publicPaths = visibility === 'PUBLIC' ? paths.filter((p) => p.trim()) : []
    try { await api.setVisibility(repo.name, { visibility, publicPaths }); setSaved(true); onChange() }
    catch (e) { setError(e instanceof Error ? e.message : 'Failed') }
  }

  return (
    <div className="Box">
      <div className="Box-header"><h2 className="Box-title">Visibility</h2></div>
      <div className="Box-body">
        <div className="stack" style={{ gap: 10 }}>
          <label className="row"><input type="radio" name="vis" checked={visibility === 'PRIVATE'} onChange={() => setVisibility('PRIVATE')} /> <b>Private</b> <span className="muted">— only collaborators</span></label>
          <label className="row"><input type="radio" name="vis" checked={visibility === 'PUBLIC'} onChange={() => setVisibility('PUBLIC')} /> <b>Public</b> <span className="muted">— anyone can read the public paths</span></label>
        </div>
        {visibility === 'PUBLIC' && (
          <div style={{ marginTop: 14 }}>
            <div className="muted" style={{ marginBottom: 6 }}>Public paths (anonymous, read-only). Leave empty for fully public (<span className="mono">**</span>):</div>
            {paths.map((p, i) => (
              <div className="row" key={i} style={{ marginBottom: 6 }}>
                <input className="form-control input-inline" placeholder="e.g. docs or frontend/**" value={p}
                       onChange={(e) => setPaths(paths.map((x, idx) => (idx === i ? e.target.value : x)))} />
                {paths.length > 1 && <button className="btn btn-danger btn-sm" onClick={() => setPaths(paths.filter((_, idx) => idx !== i))}>×</button>}
              </div>
            ))}
            <button className="btn btn-sm" onClick={() => setPaths([...paths, ''])}>Add path</button>
          </div>
        )}
        {error && <ErrorNote message={error} />}
        <div className="row" style={{ marginTop: 14 }}>
          <button className="btn btn-primary" onClick={save}>Save</button>
          {saved && <span className="tag-r">Saved</span>}
        </div>
      </div>
    </div>
  )
}
