import { useEffect, useState } from 'react'
import { api } from './api'
import { ChevronDownIcon, ChevronRightIcon, FileIcon, FolderIcon } from './icons'
import { covers } from './paths'
import { cx, ErrorNote, Spinner } from './ui'
import type { Scope, TreeEntry } from './types'

/**
 * Grant access by clicking the repository's actual files and folders, instead of typing globs.
 *
 * Selecting a folder grants its whole subtree — that is exactly how the server's matcher treats a
 * path with no wildcard, so what you click is what gets stored. Patterns that can't be expressed
 * by clicking (`*.md`) are still addable by hand and shown alongside.
 */
export type PickerMode = 'rw' | 'read'

export type Access = 'none' | 'read' | 'write'


export default function PathPicker({
  repo, mode, value, onChange,
}: {
  repo: string
  mode: PickerMode
  value: Scope[]
  onChange: (scopes: Scope[]) => void
}) {
  const [ref, setRef] = useState<string | null>(null)
  const [empty, setEmpty] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    api.branches(repo)
      .then((b) => {
        const branch = b.defaultBranch || b.branches[0] || ''
        setRef(branch)
        setEmpty(!branch)
      })
      .catch((e: Error) => setError(e.message))
  }, [repo])

  /** Access recorded for this exact path, ignoring inheritance. */
  function exact(path: string): Access {
    const hit = value.find((s) => s.path === path)
    if (!hit) return 'none'
    return hit.write ? 'write' : 'read'
  }

  /** Another selection (a parent folder, or a pattern like `*.md`) already covers this path. */
  function inheritedFrom(path: string): Scope | undefined {
    return value.find((s) => path !== s.path && covers(s.path, path))
  }

  function setAccess(path: string, access: Access) {
    const rest = value.filter((s) => s.path !== path)
    if (access === 'none') {
      onChange(rest)
      return
    }
    onChange([...rest, { path, read: true, write: access === 'write' }])
  }

  if (error) return <ErrorNote message={error} />
  if (ref === null) return <Spinner />

  return (
    <div className="picker">
      {empty ? (
        <div className="picker-empty">
          This repository has no commits yet — add path patterns manually below.
        </div>
      ) : (
        <div className="picker-tree" role="tree" aria-label="Repository files">
          <Level
            repo={repo} refName={ref} path="" depth={0}
            mode={mode} exact={exact} inheritedFrom={inheritedFrom} setAccess={setAccess}
          />
        </div>
      )}
      <SelectionSummary mode={mode} value={value} onChange={onChange} />
    </div>
  )
}

/** One directory's children; folders expand in place and load lazily. */
function Level({
  repo, refName, path, depth, mode, exact, inheritedFrom, setAccess,
}: {
  repo: string
  refName: string
  path: string
  depth: number
  mode: PickerMode
  exact: (path: string) => Access
  inheritedFrom: (path: string) => Scope | undefined
  setAccess: (path: string, access: Access) => void
}) {
  const [entries, setEntries] = useState<TreeEntry[] | null>(null)
  const [open, setOpen] = useState<Record<string, boolean>>({})
  const [error, setError] = useState('')

  useEffect(() => {
    api.tree(repo, refName, path).then(setEntries).catch((e: Error) => setError(e.message))
  }, [repo, refName, path])

  if (error) return <div className="picker-row"><ErrorNote message={error} /></div>
  if (!entries) return <div className="picker-row" style={{ paddingLeft: 12 + depth * 16 }}><Spinner /></div>
  if (entries.length === 0) {
    return <div className="picker-row muted" style={{ paddingLeft: 28 + depth * 16 }}>empty</div>
  }

  // Folders first, then files — the order people expect to scan.
  const sorted = [...entries].sort((a, b) =>
    a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'tree' ? -1 : 1)

  return (
    <>
      {sorted.map((entry) => {
        const inherited = inheritedFrom(entry.path)
        const access = exact(entry.path)
        const isOpen = open[entry.path]
        return (
          <div key={entry.path}>
            <div className={cx('picker-row', (access !== 'none' || inherited) && 'is-granted')}>
              <div className="picker-name" style={{ paddingLeft: depth * 16 }}>
                {entry.type === 'tree' ? (
                  <button
                    className="picker-twisty"
                    aria-expanded={!!isOpen}
                    aria-label={`${isOpen ? 'Collapse' : 'Expand'} ${entry.name}`}
                    onClick={() => setOpen({ ...open, [entry.path]: !isOpen })}
                  >
                    {isOpen ? <ChevronDownIcon size={12} /> : <ChevronRightIcon size={12} />}
                  </button>
                ) : <span className="picker-twisty" />}
                {entry.type === 'tree'
                  ? <FolderIcon className="ic-folder" size={14} />
                  : <FileIcon className="ic-file" size={14} />}
                <span className="picker-label" title={entry.path}>{entry.name}</span>
                {entry.type === 'tree' && <span className="picker-hint">/</span>}
              </div>

              {inherited ? (
                <span className="picker-inherited" title={`granted by ${inherited.path}`}>
                  via <code>{inherited.path}</code>
                </span>
              ) : (
                <AccessControl
                  mode={mode}
                  access={access}
                  onSet={(a) => setAccess(entry.path, a)}
                  label={entry.path}
                />
              )}
            </div>
            {entry.type === 'tree' && isOpen && (
              <Level
                repo={repo} refName={refName} path={entry.path} depth={depth + 1}
                mode={mode} exact={exact} inheritedFrom={inheritedFrom} setAccess={setAccess}
              />
            )}
          </div>
        )
      })}
    </>
  )
}

/** None / Read / Write for collaborators; a single toggle for public paths. */
function AccessControl({
  mode, access, onSet, label,
}: {
  mode: PickerMode
  access: Access
  onSet: (a: Access) => void
  label: string
}) {
  if (mode === 'read') {
    return (
      <button
        className={cx('picker-toggle', access !== 'none' && 'on')}
        aria-pressed={access !== 'none'}
        onClick={() => onSet(access === 'none' ? 'read' : 'none')}
      >
        {access === 'none' ? 'Private' : 'Public'}
      </button>
    )
  }
  const options: Array<{ key: Access; text: string }> = [
    { key: 'none', text: 'No access' },
    { key: 'read', text: 'Read' },
    { key: 'write', text: 'Write' },
  ]
  return (
    <div className="segmented" role="group" aria-label={`Access to ${label}`}>
      {options.map((o) => (
        <button
          key={o.key}
          className={cx('segmented-item', access === o.key && 'selected')}
          aria-pressed={access === o.key}
          onClick={() => onSet(o.key)}
        >
          {o.text}
        </button>
      ))}
    </div>
  )
}

/** Everything currently granted, including hand-written globs the tree can't represent. */
function SelectionSummary({
  mode, value, onChange,
}: {
  mode: PickerMode
  value: Scope[]
  onChange: (scopes: Scope[]) => void
}) {
  const [pattern, setPattern] = useState('')

  function addPattern() {
    const path = pattern.trim()
    if (!path || value.some((s) => s.path === path)) { setPattern(''); return }
    onChange([...value, { path, read: true, write: false }])
    setPattern('')
  }

  return (
    <div className="picker-summary">
      <div className="picker-summary-head">
        {value.length === 0
          ? <span className="muted">{mode === 'read' ? 'Nothing public yet — the repository stays fully private.' : 'No paths selected — this grants access to the whole repository.'}</span>
          : <span className="muted">{value.length} path{value.length > 1 ? 's' : ''} selected</span>}
      </div>
      {value.length > 0 && (
        <div className="chips">
          {[...value].sort((a, b) => a.path.localeCompare(b.path)).map((s) => (
            <span className="chip" key={s.path}>
              <code>{s.path}</code>
              {mode === 'rw' && <span className={cx('chip-access', s.write && 'write')}>{s.write ? 'write' : 'read'}</span>}
              <button
                className="chip-x"
                aria-label={`Remove ${s.path}`}
                onClick={() => onChange(value.filter((x) => x.path !== s.path))}
              >×</button>
            </span>
          ))}
        </div>
      )}
      <div className="picker-pattern">
        <input
          className="form-control"
          placeholder="Add a pattern, e.g. *.md or docs/**"
          value={pattern}
          onChange={(e) => setPattern(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addPattern() } }}
        />
        <button className="btn btn-sm" onClick={addPattern} disabled={!pattern.trim()}>Add</button>
      </div>
    </div>
  )
}
