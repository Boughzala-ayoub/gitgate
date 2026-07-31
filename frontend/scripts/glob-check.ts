// Asserts the browser's path matcher agrees with the server's GlobMatcher.
// Same cases as src/test/java/dev/gitgate/acl/GlobMatcherTest.java — if the two drift, the
// settings UI starts mislabelling which files a person can see.
//
//   npm run check:paths
import assert from 'node:assert/strict'
import { covers, excludedBy, grantedBy } from '../src/paths.ts'

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

// Exclusions: the picker must resolve access the same way AclService does — deny wins, and a
// folder exclusion hides its subtree.
const rules = [
  { path: 'backend', excluded: false },
  { path: 'backend/secrets.env', excluded: true },
  { path: 'vault', excluded: true },
]
assert.ok(grantedBy(rules, 'backend/db.js'), 'backend/db.js is granted by the backend rule')
assert.equal(excludedBy(rules, 'backend/db.js'), undefined, 'and is not excluded')
assert.ok(excludedBy(rules, 'backend/secrets.env'), 'the carve-out is excluded')
assert.ok(excludedBy(rules, 'vault/key.pem'), 'excluding a folder hides its subtree')
assert.equal(grantedBy(rules, 'frontend/app.js'), undefined, 'unrelated paths stay ungranted')

console.log(`PASS — ${grants.length + denies.length} path-matching and 5 exclusion cases agree with the server.`)
