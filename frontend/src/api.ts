import type {
  AdminUser, AuditEntry, Blob, Branches, Collaborator, Me, Repo, Scope, TreeEntry, Visibility,
} from './types'

// Auth is HTTP Basic (username + password/token), kept in localStorage.
let auth = localStorage.getItem('gitgate_auth') || ''

export function setAuth(user: string, password: string): void {
  auth = 'Basic ' + btoa(`${user}:${password}`)
  localStorage.setItem('gitgate_auth', auth)
}

export function clearAuth(): void {
  auth = ''
  localStorage.removeItem('gitgate_auth')
}

export function hasAuth(): boolean {
  return !!auth
}

export interface ApiError extends Error {
  auth?: boolean
}

async function handle<T>(res: Response): Promise<T> {
  if (res.status === 401 || res.status === 403) {
    const err: ApiError = new Error('Not authorized')
    err.auth = true
    throw err
  }
  if (!res.ok) {
    const data = await res.json().catch(() => ({ error: res.statusText }))
    throw new Error(data.error || 'Request failed')
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

function req<T>(method: string, path: string, body?: unknown): Promise<T> {
  return fetch('/api' + path, {
    method,
    headers: { Authorization: auth, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  }).then((r) => handle<T>(r))
}

const q = encodeURIComponent

export const api = {
  signup: (username: string, password: string): Promise<Me> =>
    fetch('/api/auth/signup', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    }).then((r) => handle<Me>(r)),

  me: (): Promise<Me> => req('GET', '/me'),
  myRepos: (): Promise<Repo[]> => req('GET', '/me/repos'),
  explore: (query: string): Promise<Repo[]> => req('GET', `/explore?q=${q(query)}`),
  createRepo: (name: string): Promise<Repo> => req('POST', '/repos', { name, visibility: 'PRIVATE' }),
  getRepo: (name: string): Promise<Repo> => req('GET', `/repos/${name}`),
  deleteRepo: (name: string): Promise<void> => req('DELETE', `/repos/${name}`),
  setVisibility: (name: string, body: { visibility: Visibility; publicPaths: string[] }): Promise<Repo> =>
    req('PUT', `/repos/${name}/visibility`, body),

  collaborators: (name: string): Promise<Collaborator[]> => req('GET', `/repos/${name}/collaborators`),
  addCollaborator: (name: string, body: { username: string; role: string; scopes: Scope[] }): Promise<Collaborator> =>
    req('POST', `/repos/${name}/collaborators`, body),
  removeCollaborator: (name: string, username: string): Promise<void> =>
    req('DELETE', `/repos/${name}/collaborators/${username}`),

  branches: (name: string): Promise<Branches> => req('GET', `/browse/${name}/branches`),
  tree: (name: string, ref: string, path: string): Promise<TreeEntry[]> =>
    req('GET', `/browse/${name}/tree?ref=${q(ref)}&path=${q(path)}`),
  blob: (name: string, ref: string, path: string): Promise<Blob> =>
    req('GET', `/browse/${name}/blob?ref=${q(ref)}&path=${q(path)}`),

  adminUsers: (): Promise<AdminUser[]> => req('GET', '/admin/users'),
  adminMintToken: (id: number, label: string): Promise<{ token: string }> =>
    req('POST', `/admin/users/${id}/tokens`, { label }),
  adminAudit: (): Promise<AuditEntry[]> => req('GET', '/admin/audit'),
}
