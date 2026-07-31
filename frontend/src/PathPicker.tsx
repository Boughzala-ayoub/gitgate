import { useEffect, useState } from 'react'
import { api } from './api'
import { ChevronDownIcon, ChevronRightIcon, EyeClosedIcon, FileIcon, FolderIcon } from './icons'
import { excludedBy, grantedBy } from './paths'
import { cx, ErrorNote, Spinner } from './ui'
import type { Scope, TreeEntry } from './types'

/**
 * Grant access by clicking the repository's actual files and folders, instead of typing globs.
 *
 * Two shapes of rule, matching the server: a **grant** (this path, and its subtree if it's a
 * folder) and an **exclusion**, which carves a path back out and always wins. That pairing is
 * what makes "the whole backend folder except its secrets" and "public except this file"
 * expressible at all.
 */
export type PickerMode = 'rw' | 'read'

export type Access = 'none' | 'read' | 'write'

/**
 * How a row got its access — everything the row UI needs.
 *
 * `openByDefault` covers the public case with no explicit paths: the repo is fully public, so
 * every row is already visible even though no grant names it.
 */
type RowState =
  | { kind: 'excluded'; via?: string }
  | { kind: 'inherited'; via: string; write: boolean }
  | { kind: 'open' }
  | { kind: 'explicit'; access: Access }

export default function PathPicker({
  repo, mode, value, onChange, wholeRepo = false,
}: {
  repo: string
  mode: PickerMode
  value: Scope[]
  onChange: (scopes: Scope[]) => void
  /**
   * Everything is granted up front and the job is to carve paths back out — the shape the server
   * resolves from a membership with no grants and N exclusions. Read mode is always like this
   * when nothing is marked public.
   */
  wholeRepo?: boolean
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

  const grants = value.filter((s) => !s.excluded)
  // Nothing selected means "all of it" in two cases: a public repo with no explicit public paths,
  // and a collaborator granted the entire repository. Otherwise a row is off until granted.
  const openByDefault = grants.length === 0 && (mode === 'read' || wholeRepo)

  function stateOf(path: string): RowState {
    const exclusion = excludedBy(value, path)
    if (exclusion) {
      return { kind: 'excluded', via: exclusion.path === path ? undefined : exclusion.path }
    }
    const exact = value.find((s) => s.path === path && !s.excluded)
    if (exact) return { kind: 'explicit', access: exact.write ? 'write' : 'read' }

    const inherited = grantedBy(grants, path)
    if (inherited) return { kind: 'inherited', via: inherited.path, write: !!inherited.write }

    return openByDefault ? { kind: 'open' } : { kind: 'explicit', access: 'none' }
  }

  function setAccess(path: string, access: Access) {
    const rest = value.filter((s) => s.path !== path)
    if (access === 'none') {
      onChange(rest)
      return
    }
    onChange([...rest, { path, read: true, write: access === 'write' }])
  }

  function exclude(path: string) {
    onChange([...value.filter((s) => s.path !== path), { path, read: false, write: false, excluded: true }])
  }

  function clearRule(path: string) {
    onChange(value.filter((s) => s.path !== path))
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
            repo={repo} refName={ref} path="" depth={0} mode={mode}
            stateOf={stateOf} setAccess={setAccess} exclude={exclude} clearRule={clearRule}
          />
        </div>
      )}
      <SelectionSummary mode={mode} value={value} onChange={onChange} openByDefault={openByDefault} />
    </div>
  )
}

/** One directory's children; folders expand in place and load lazily. */
function Level({
  repo, refName, path, depth, mode, stateOf, setAccess, exclude, clearRule,
}: {
  repo: string
  refName: string
  path: string
  depth: number
  mode: PickerMode
  stateOf: (path: string) => RowState
  setAccess: (path: string, access: Access) => void
  exclude: (path: string) => void
  clearRule: (path: string) => void
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

  const sorted = [...entries].sort((a, b) =>
    a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'tree' ? -1 : 1)

  return (
    <>
      {sorted.map((entry) => {
        const state = stateOf(entry.path)
        const isOpen = open[entry.path]
        const granted = state.kind === 'inherited' || state.kind === 'open'
          || (state.kind === 'explicit' && state.access !== 'none')
        return (
          <div key={entry.path}>
            <div className={cx('picker-row', granted && 'is-granted', state.kind === 'excluded' && 'is-excluded')}>
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
                <span className={cx('picker-label', state.kind === 'excluded' && 'struck')} title={entry.path}>
                  {entry.name}
                </span>
                {entry.type === 'tree' && <span className="picker-hint">/</span>}
              </div>

              <RowControl
                mode={mode}
                state={state}
                path={entry.path}
                onSet={(a) => setAccess(entry.path, a)}
                onExclude={() => exclude(entry.path)}
                onClear={() => clearRule(entry.path)}
              />
            </div>
            {entry.type === 'tree' && isOpen && (
              <Level
                repo={repo} refName={refName} path={entry.path} depth={depth + 1} mode={mode}
                stateOf={stateOf} setAccess={setAccess} exclude={exclude} clearRule={clearRule}
              />
            )}
          </div>
        )
      })}
    </>
  )
}

function RowControl({
  mode, state, path, onSet, onExclude, onClear,
}: {
  mode: PickerMode
  state: RowState
  path: string
  onSet: (a: Access) => void
  onExclude: () => void
  onClear: () => void
}) {
  if (state.kind === 'excluded') {
    return (
      <span className="picker-status">
        <span className="pill pill-hidden"><EyeClosedIcon size={12} /> Hidden</span>
        {state.via
          ? <span className="picker-inherited">via <code>{state.via}</code></span>
          : <button className="btn-link" onClick={onClear}>undo</button>}
      </span>
    )
  }

  // Covered by a parent grant (or public-by-default): the useful action here is to carve it out.
  if (state.kind === 'inherited' || state.kind === 'open') {
    return (
      <span className="picker-status">
        {state.kind === 'inherited'
          ? <span className="picker-inherited">via <code>{state.via}</code></span>
          : <span className="picker-inherited">{mode === 'read' ? 'public' : 'included'}</span>}
        <button className="btn btn-sm" onClick={onExclude} title={`Hide ${path}`}>
          <EyeClosedIcon size={12} /> Exclude
        </button>
      </span>
    )
  }

  if (mode === 'read') {
    const on = state.access !== 'none'
    return (
      <span className="picker-status">
        <button
          className={cx('picker-toggle', on && 'on')}
          aria-pressed={on}
          onClick={() => onSet(on ? 'none' : 'read')}
        >
          {on ? 'Public' : 'Private'}
        </button>
        {on && <button className="btn btn-sm" onClick={onExclude} title={`Hide ${path}`}>Exclude</button>}
      </span>
    )
  }

  // Write always includes read: the server merges a push using the paths the pusher can see, so
  // write-without-read would silently drop their changes. One choice, three levels.
  const options: Array<{ key: Access; text: string; title: string }> = [
    { key: 'none', text: 'No access', title: 'Hidden from them entirely' },
    { key: 'read', text: 'Read', title: 'Can clone and browse, cannot change' },
    { key: 'write', text: 'Read + write', title: 'Can clone, browse and push changes' },
  ]
  return (
    <div className="segmented" role="group" aria-label={`Access to ${path}`}>
      {options.map((o) => (
        <button
          key={o.key}
          className={cx('segmented-item', state.access === o.key && 'selected')}
          aria-pressed={state.access === o.key}
          title={o.title}
          onClick={() => onSet(o.key)}
        >
          {o.text}
        </button>
      ))}
    </div>
  )
}

/** Everything currently granted or hidden, including patterns the tree can't represent. */
function SelectionSummary({
  mode, value, onChange, openByDefault,
}: {
  mode: PickerMode
  value: Scope[]
  onChange: (scopes: Scope[]) => void
  openByDefault: boolean
}) {
  const [pattern, setPattern] = useState('')
  const [asExclusion, setAsExclusion] = useState(false)

  function addPattern() {
    const path = pattern.trim()
    if (!path || value.some((s) => s.path === path)) { setPattern(''); return }
    onChange([...value, { path, read: !asExclusion, write: false, excluded: asExclusion }])
    setPattern('')
  }

  const grants = value.filter((s) => !s.excluded)
  const exclusions = value.filter((s) => s.excluded)

  return (
    <div className="picker-summary">
      <div className="picker-summary-head">
        {grants.length === 0 ? (
          <span className="muted">
            {mode === 'read'
              ? (openByDefault ? 'The entire repository is public.' : 'Nothing public yet.')
              // In "specific paths" mode with nothing picked, the server still resolves this to
              // the whole repository — say so rather than let it surprise them on save.
              : (openByDefault
                ? 'The entire repository.'
                : 'No paths picked yet — saving now would grant the entire repository.')}
          </span>
        ) : (
          <span className="muted">{grants.length} path{grants.length > 1 ? 's' : ''} granted</span>
        )}
        {exclusions.length > 0 && (
          <span className="muted"> · {exclusions.length} hidden</span>
        )}
      </div>

      {value.length > 0 && (
        <div className="chips">
          {[...value].sort((a, b) => a.path.localeCompare(b.path)).map((s) => (
            <span className={cx('chip', s.excluded && 'chip-excluded')} key={s.path}>
              {s.excluded && <EyeClosedIcon size={11} />}
              <code>{s.path}</code>
              {s.excluded
                ? <span className="chip-access hidden">hidden</span>
                : mode === 'rw' && (
                  <span className={cx('chip-access', s.write && 'write')}>
                    {s.write ? 'read + write' : 'read'}
                  </span>
                )}
              <button
                className="chip-x"
                aria-label={`Remove rule for ${s.path}`}
                onClick={() => onChange(value.filter((x) => x.path !== s.path))}
              >×</button>
            </span>
          ))}
        </div>
      )}

      <div className="picker-pattern">
        <input
          className="form-control"
          placeholder={asExclusion ? 'Hide a pattern, e.g. **/*.env' : 'Add a pattern, e.g. *.md or docs/**'}
          value={pattern}
          onChange={(e) => setPattern(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addPattern() } }}
        />
        <label className="picker-pattern-mode">
          <input type="checkbox" checked={asExclusion} onChange={(e) => setAsExclusion(e.target.checked)} />
          hide
        </label>
        <button className="btn btn-sm" onClick={addPattern} disabled={!pattern.trim()}>Add</button>
      </div>
    </div>
  )
}
