/**
 * Client-side mirror of the server's `dev.gitgate.acl.GlobMatcher`.
 *
 * The picker uses this only to *explain* access — the server remains the authority — but if the
 * two disagree the UI tells people a file is private when it isn't, which is the worst kind of
 * wrong. `scripts/glob-check.ts` asserts the same cases as the Java `GlobMatcherTest`.
 *
 * Rules: `**` spans directories, `*` and `?` never cross `/`, and a pattern with no wildcard
 * also grants its entire subtree (`backend` covers `backend/db.js`).
 */
export function globToRegex(glob: string): RegExp {
  let out = '^'
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i]
    if (c === '*') {
      if (glob[i + 1] === '*') { out += '.*'; i++ } else { out += '[^/]*' }
    } else if (c === '?') {
      out += '[^/]'
    } else if ('.()+|^$[]{}\\'.includes(c)) {
      out += '\\' + c
    } else {
      out += c
    }
  }
  return new RegExp(out + '$')
}

/** True if `pattern` covers `path` (as a grant or as an exclusion — the rule is the same). */
export function covers(pattern: string, path: string): boolean {
  if (globToRegex(pattern).test(path)) return true
  const plain = !/[*?]/.test(pattern)
  return plain && path.startsWith(pattern + '/')
}

/** The minimum a rule needs for access to be explained. */
export interface Rule {
  path: string
  excluded?: boolean
}

/**
 * The exclusion covering `path`, if any. Checked first everywhere, because on the server an
 * exclusion always beats a grant — the UI must not suggest otherwise.
 */
export function excludedBy<T extends Rule>(rules: T[], path: string): T | undefined {
  return rules.find((r) => r.excluded && covers(r.path, path))
}

/** The grant covering `path`, if any. */
export function grantedBy<T extends Rule>(rules: T[], path: string): T | undefined {
  return rules.find((r) => !r.excluded && covers(r.path, path))
}
