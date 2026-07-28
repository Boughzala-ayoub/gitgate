// Asserts the browser's path matcher agrees with the server's GlobMatcher.
// Same cases as src/test/java/dev/gitgate/acl/GlobMatcherTest.java — if the two drift, the
// settings UI starts mislabelling which files a person can see.
//
//   npm run check:paths
import assert from 'node:assert/strict'
import { covers } from '../src/paths.ts'

const grants: Array<[string, string]> = [
  ['backend', 'backend'],
  ['backend', 'backend/db.js'],
  ['backend', 'backend/deep/nested/file.txt'],
  ['*.md', 'README.md'],
  ['**', 'a/b/c.txt'],
  ['docs/**', 'docs/a/b.txt'],
  ['log?.txt', 'log1.txt'],
  ['a+b(c).txt', 'a+b(c).txt'],
]

const denies: Array<[string, string]> = [
  ['backend', 'backend-secrets/db.js'],
  ['backend', 'frontend/app.js'],
  ['*.md', 'docs/guide.md'],       // * must not cross a directory separator
  ['*.md', 'README.txt'],
  ['docs/**', 'src/a.txt'],
  ['log?.txt', 'log12.txt'],       // ? is exactly one character
  ['log?.txt', 'log/.txt'],
  ['a+b(c).txt', 'aab(c)Xtxt'],    // regex metacharacters are literal
]

for (const [pattern, path] of grants) {
  assert.equal(covers(pattern, path), true, `"${pattern}" should grant "${path}"`)
}
for (const [pattern, path] of denies) {
  assert.equal(covers(pattern, path), false, `"${pattern}" should NOT grant "${path}"`)
}

console.log(`PASS — ${grants.length + denies.length} path-matching cases agree with the server.`)
