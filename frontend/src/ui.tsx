import { useEffect, useState } from 'react'
import { GlobeIcon, LockIcon } from './icons'
import type { Visibility } from './types'

export function cx(...parts: Array<string | false | undefined | null>): string {
  return parts.filter(Boolean).join(' ')
}

/** Minimal data-loading hook with reload + cancellation. */
export function useAsync<T>(fn: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState('')
  const [key, setKey] = useState(0)
  const reload = () => setKey((k) => k + 1)
  useEffect(() => {
    let alive = true
    setError('')
    fn().then((d) => { if (alive) setData(d) }).catch((e: Error) => { if (alive) setError(e.message) })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, ...deps])
  return { data, error, reload }
}

export function Spinner() {
  return <span className="spinner" role="status" aria-label="Loading" />
}

export function ErrorNote({ message }: { message: string }) {
  return <div className="flash flash-error">{message}</div>
}

export function VisibilityLabel({ visibility }: { visibility: Visibility }) {
  const isPublic = visibility === 'PUBLIC'
  return (
    <span className="Label">
      {isPublic ? <GlobeIcon size={12} /> : <LockIcon size={12} />}
      {isPublic ? 'Public' : 'Private'}
    </span>
  )
}
