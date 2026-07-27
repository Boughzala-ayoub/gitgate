import { useEffect, useState } from 'react'
import { api } from './api'
import { BranchIcon, ChevronDownIcon, FileIcon, FolderIcon } from './icons'
import { ErrorNote, Spinner } from './ui'
import type { Blob, Branches, TreeEntry } from './types'

export default function CodeBrowser({ name }: { name: string }) {
  const [branches, setBranches] = useState<Branches | null>(null)
  const [ref, setRef] = useState('')
  const [path, setPath] = useState('')
  const [entries, setEntries] = useState<TreeEntry[]>([])
  const [file, setFile] = useState<Blob | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    api.branches(name)
      .then((b) => { setBranches(b); setRef(b.defaultBranch || b.branches[0] || '') })
      .catch((e: Error) => setError(e.message))
  }, [name])

  useEffect(() => {
    if (!branches) return
    setFile(null)
    api.tree(name, ref, path).then(setEntries).catch((e: Error) => setError(e.message))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ref, path, branches])

  function openEntry(e: TreeEntry) {
    setError('')
    if (e.type === 'tree') setPath(e.path)
    else api.blob(name, ref, e.path).then(setFile).catch((err: Error) => setError(err.message))
  }

  const crumbs = path ? path.split('/') : []

  if (error) return <div className="Box"><div className="Box-body"><ErrorNote message={error} /></div></div>
  if (!branches) return <div className="Box"><div className="Box-body"><Spinner /></div></div>
  if (branches.branches.length === 0) {
    return <div className="Box"><div className="blankslate"><FileIcon size={24} /><p>This repository is empty.</p></div></div>
  }

  return (
    <div className="Box">
      <div className="Box-header" style={{ gap: 12, flexWrap: 'wrap' }}>
        <span className="btn btn-sm" style={{ cursor: 'default' }}>
          <BranchIcon size={14} />
          {branches.branches.length > 1 ? (
            <select className="branch-select" value={ref} onChange={(e) => { setRef(e.target.value); setPath('') }}>
              {branches.branches.map((b) => <option key={b}>{b}</option>)}
            </select>
          ) : <strong>{ref}</strong>}
          <ChevronDownIcon size={12} />
        </span>
        <span className="breadcrumb" style={{ fontSize: 14 }}>
          <button className="crumb root" onClick={() => { setPath(''); setFile(null) }}>{name}</button>
          {crumbs.map((c, i) => (
            <span key={i}>
              <span className="sep"> / </span>
              <button className="crumb" onClick={() => { setPath(crumbs.slice(0, i + 1).join('/')); setFile(null) }}>{c}</button>
            </span>
          ))}
        </span>
      </div>

      {file ? <BlobView file={file} onBack={() => setFile(null)} /> : (
        <div>
          {entries.length === 0 && <div className="blankslate"><p>This folder is empty.</p></div>}
          {entries.map((e) => (
            <div className="file-row" key={e.path}>
              {e.type === 'tree' ? <FolderIcon className="ic-folder" /> : <FileIcon className="ic-file" />}
              <button className="btn-link" style={{ color: 'inherit' }} onClick={() => openEntry(e)}>{e.name}</button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function BlobView({ file, onBack }: { file: Blob; onBack: () => void }) {
  const lines = file.content ? file.content.replace(/\n$/, '').split('\n') : []
  return (
    <>
      <div className="Box-row between">
        <span className="mono">{file.path} <span className="muted">· {file.size} bytes</span></span>
        <button className="btn btn-sm" onClick={onBack}>Back to files</button>
      </div>
      {file.tooLarge ? <div className="blankslate">File is too large to display.</div>
        : file.binary ? <div className="blankslate">Binary file not shown.</div>
          : (
            <div className="blob">
              <div className="gutter">{lines.map((_, i) => `${i + 1}\n`).join('')}</div>
              <div className="code">{file.content}</div>
            </div>
          )}
    </>
  )
}
