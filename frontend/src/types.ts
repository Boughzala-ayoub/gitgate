export interface Me {
  username: string
  superadmin: boolean
}

export type Visibility = 'PUBLIC' | 'PRIVATE'

export interface Repo {
  name: string
  owner: string | null
  visibility: Visibility
  myRole: string
  publicPaths: string[]
  publicExclusions: string[]
}

export interface Scope {
  path: string
  read: boolean
  write: boolean
  /** Carved out of the grant rather than added to it — an exclusion always wins. */
  excluded?: boolean
}

export interface Collaborator {
  username: string
  role: string
  scopes: Scope[]
}

export interface Branches {
  defaultBranch: string | null
  branches: string[]
}

export interface TreeEntry {
  name: string
  type: 'tree' | 'blob'
  path: string
}

export interface Blob {
  path: string
  size: number
  binary: boolean
  tooLarge: boolean
  content: string | null
}

export interface AdminUser {
  id: number
  username: string
  roles: string[]
  tokenCount: number
}

export interface AuditEntry {
  ts: string
  username: string
  repo: string
  action: string
  ref: string | null
  detail: string | null
}
