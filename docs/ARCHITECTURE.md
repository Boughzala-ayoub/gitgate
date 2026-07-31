# GitGate Architecture

How GitGate gives each user a different slice of the same Git repository — enforced on the
server, over the wire, not faked in the working tree.

If you only read one section, read [The core problem](#1-the-core-problem-git-cannot-hide-files)
and [The read path](#3-the-read-path-view-materialization). Everything else follows from them.

---

## 1. The core problem: Git cannot hide files

Git's data model is a Merkle DAG. A commit hashes its tree; a tree hashes its subtrees and
blobs; every object's ID *is* the hash of its contents. When you `git clone`, you receive
**every object reachable from the refs** — the full history of every file. There is no native
way to hand someone *part* of a repository.

This is not a missing feature; it is a consequence of the design. And it is expensive in
practice:

- **GitHub / GitLab** offer only repo-level permissions. File-level access is one of their
  most-requested, never-shipped features.
- **Perforce / Helix** is bought by entire industries (games, film) largely *because* it has
  path-level ACLs — they pay to escape this exact Git limitation.
- Mixed-sensitivity monorepos, contractor access, and secrets-in-history are constant pain.

GitGate closes the gap: **one canonical repository, many per-user filtered views, enforced
server-side.**

---

## 2. The central challenge: a filtered view is a *different commit graph*

You cannot just "skip" forbidden objects when serving a clone. Remove a path from a tree and
that tree's hash changes; the commit that points to it changes; and because each commit hashes
its parent, *every descendant commit changes too*. A filtered view is therefore a **completely
different commit graph** from the canonical repo — different tree IDs, different commit IDs.

So the engine has two hard jobs:

1. **Read** — deterministically rewrite canonical history into a per-user graph that contains
   only readable paths (and never reveals that the others exist).
2. **Write** — take a push made against that rewritten graph and map it *back* onto canonical
   history without touching anything the user couldn't see.

These two pieces are the moat. Everything around them (auth, the dashboard, public repos, the
web code browser) is a conventional SaaS layer over an unchanged engine.

---

## 3. The read path: view materialization

`git.ViewMaterializer` rewrites a canonical repository into a filtered view for a given
permission set.

For each branch, walking history:

- **Trees** are rewritten to contain only readable entries (`filterTree`). A subtree with no
  readable entries is **omitted entirely** — not emptied — so hidden files leak neither their
  contents nor their *names or existence*.
- **Commits** are rebuilt with the filtered tree and with parents rewritten to their view
  equivalents. Author, committer, message, encoding, and timestamps are preserved, so the view
  reads as honest history.
- **Blobs are never copied.** A readable blob keeps its original ID; the view repository
  resolves it through an `objects/info/alternates` link back to the canonical object store.
  Only new trees and commits are written. Views are cheap.

The ancestry walk is **iterative, not recursive**. History is as deep as the repository has
commits, so recursing once per commit overflows the JVM stack on any real repository — an
explicit stack keeps rewriting O(1) in stack depth regardless of history length. The same
applies to the reconstruction on the push side, where a first push carries the entire history.
(`ViewMaterializerTest.deepHistoryDoesNotOverflowTheStack` pins this, materializing 2 000
commits on a deliberately small thread stack.)

The `alternates` link is verified on **every** open, not just at creation. Without it a view
resolves no blobs at all and fetches fail with an opaque server error, so a view directory that
lost the link — an interrupted cleanup, a restored backup — repairs itself rather than staying
permanently broken.

### The property that makes it usable: determinism

The rewrite is a **pure function of `(canonical commit, permission set)`**. The same view
always produces the same object IDs. That is not a nicety — it is what makes `git fetch` work
**incrementally**: on the next fetch, commits the client already has rewrite to the identical
IDs, so only genuinely new objects travel. Without determinism, every fetch would look like a
brand-new history.

Identical permission sets also share a view. `acl.ReadView` carries a `viewId` — the first
16 hex of a SHA‑1 over the sorted read-globs — so every user with the same read access shares
one materialized view (and, eventually, one cache entry).

> **Status:** the view is currently materialized on demand and memoized within a pass (correct
> and idempotent because it's deterministic). Persistent, cross-request caching of view graphs
> is a planned optimization, not a correctness requirement.

```
        canonical history                 filtered view (alice: frontend/, *.md)
        ─────────────────                 ────────────────────────────────────
        C3  tree{frontend,backend,        C3' tree{frontend, README.md}
        │        README, TOPSECRET}       │        (backend/, TOPSECRET omitted)
        C2  ...                           C2' ...
        │                                 │     blobs: same IDs, resolved via
        C1                                C1'    alternates → canonical store
```

Because the view *is* a real (if rewritten) repository, every downstream feature that reads it
inherits access control for free — the in-browser code browser (`api.BrowseService`) serves
strictly from the viewer's materialized view, so a hidden file returns 404 even by direct URL.

---

## 4. The write path: push reconciliation

A user clones their filtered view, commits, and pushes. Their push is expressed against the
*rewritten* graph — it knows nothing of the hidden paths. `git.PushReconciler` (a JGit
pre-receive hook) maps it back onto canonical history.

For each pushed commit, `reconstruct` rebuilds the canonical equivalent by **merging trees**
(`mergeTree`):

- **Readable paths** are taken from the user's new tree (a missing readable entry means they
  deleted it; new blobs are copied into the canonical store).
- **Every other path** is carried over **unchanged** from the canonical parent.

This merge is why a push **cannot** delete or alter a file the user can't see — the hidden
content is structurally re-inserted from canonical, never sourced from the push. It's not a
check that could be forgotten; it's the shape of the operation.

On top of that, `enforceWriteAcl` diffs the pushed tree against its parent and **rejects** any
change to a path that is readable but not writable (e.g. a contractor editing a read-only
`README.md`, or injecting a file into a directory they can only read).

Finally the canonical ref is updated with `setExpectedOldObjectId` — optimistic concurrency. If
canonical moved under the push, the client gets *"stale base; pull and retry"* rather than a
lost update. The mapping from pushed view-commits back to canonical commits is the **inverse**
of the memo map the materializer produced for the read side; the two halves are duals.

```
   alice pushes (against her view)        server reconstructs canonical
   ──────────────────────────────        ───────────────────────────────────
   newView tree{frontend, README}   ─►    merge: frontend ← alice's push
                                                  README   ← alice's push (write-ACL checked)
                                                  backend  ← preserved from canonical parent
                                                  TOPSECRET← preserved from canonical parent
                                          ─► fast-forward canonical ref (CAS on old id)
```

---

## 5. Access resolution

`acl.AclService.resolve(user, repo)` returns a `ReadView` (read globs + write globs + exclusions,
compiled into `GlobMatcher`s). Precedence:

1. **Anonymous** → read-only view of the repo's *public path globs* if it's public; otherwise
   nothing.
2. **Superadmin** → full access.
3. **Repo owner** → full access.
4. **Collaborator** → their membership's per-path scopes (or whole-repo by role if no scopes).
5. **Non-member** → the public view if the repo is public; otherwise an empty view.

The git engine never sees roles or memberships — it just asks for a `ReadView` and materializes
against its matchers. That separation is why the same novel engine powers private repos,
path-scoped collaborators, and partially-public repos without special cases.

### Exclusions

Grants alone can't express "everything except this". A `ReadView` therefore carries **exclusions**
alongside its globs, and `GlobMatcher.isReadable` is `matches(allow) && !matches(deny)` — an
exclusion always wins, regardless of how specific a competing grant is, so an operator cannot
accidentally re-grant a path they carved out. Exclusions apply to the write matcher too: you
cannot push a path you aren't allowed to see.

This is what makes the two headline cases possible — a **public repo with files that never leave
the server**, and a collaborator with **write access to a folder but no sight of one file in it**.

Two consequences worth stating:

- **Exclusions feed `viewId`.** Views are cached on disk under that id and shared by everyone who
  resolves to it, so if only the grants were hashed, a user who hid a file would be served the
  cached view of a user who hadn't.
- **The `/a/` clone path.** Git sends credentials only after a `401`, and a public repo never
  issues one — so a member cloning a public repo would silently get the anonymous view. The auth
  filter treats `/a/<repo>.git` as always-authenticated, giving members a URL that resolves to
  *their* view. `auth.BasicAuthFilter.isAuthenticatedPath`.

---

## 6. Request flow

```
  git clone / fetch / push                 REST API + dashboard
  (HTTP Basic: user + token)               (/api/**, anonymous tier for public)
            │                                        │
            ▼                                        ▼
  ┌───────────────────┐                    ┌───────────────────────┐
  │ auth.BasicAuthFilter│  identifies user │ api.* controllers      │
  │ + AnonymousRateLimiter (public reads)  │ (repos, collaborators, │
  └─────────┬─────────┘                    │  visibility, browse,   │
            │                              │  explore, admin)       │
            ▼                              └───────────┬───────────┘
  ┌───────────────────┐   ReadView                    │
  │ acl.AclService     │◄─────────────────────────────┘
  │  resolve(user,repo)│
  └─────────┬─────────┘
            │ matchers
            ▼
  ┌──────────────────────────────────────────────┐
  │ git.ViewMaterializer  (canonical → view)       │  reads
  │ git.PushReconciler    (view push → canonical)  │  writes
  └─────────┬───────────────────────────┬─────────┘
            ▼                            ▼
  ┌────────────────────┐      ┌────────────────────────┐
  │ canonical bare repos│      │ H2 (JPA): users, repos, │
  │ (JGit, full content)│      │ memberships, scopes,    │
  │ + per-view repos via│      │ tokens, audit log       │
  │   alternates        │      └────────────────────────┘
  └────────────────────┘
```

---

## 7. Security properties & threat model

Stating limits precisely is part of the design — it's how you tell a real security boundary
from a fake one.

**Trusted:** the server and its administrators. They hold the full canonical content.

**Protected against:**
- An authenticated user **reading** paths outside their ACL — forbidden objects are never sent
  over the wire, not merely hidden in the checkout. (A user cannot fetch a hidden blob even by
  its exact SHA; see [§8](#8-how-the-guarantees-are-verified).)
- An authenticated user **writing** paths outside their write-ACL.
- Leakage of hidden files' **existence or names** — filtered trees omit entries entirely.

**Not protected against (inherent limitations):**
- A malicious server or admin — they have everything by definition.
- **Revocation of already-cloned data** — bytes a user already received are theirs; ACL changes
  apply going forward only. Inherent to any non-DRM system.
- **Side channels** — a commit *message*, or an allowed file, may reference a hidden path.
- Traffic analysis (object sizes, timing).
- **Signed commits** cannot survive rewriting — signatures break when trees and parents change.

---

## 8. How the guarantees are verified

Security claims are only worth what tests them. The suite (`mvn verify`, run by CI on every push)
is built so that the properties above fail loudly if they regress.

**End to end, over the wire** — `GitOverHttpTest` starts the server on a random port and drives
**real git clients** against it, which is the only way to prove what actually travels:

| Property | How it's tested |
|---|---|
| Each user gets a different slice of one URL | alice, bob and admin clone `app.git`; the three checkouts are asserted file-by-file |
| Forbidden objects are never transmitted | the hidden blob's exact SHA is looked up in alice's clone's object database — absent |
| Private repos reject anonymous access | an unauthenticated clone must fail |
| A push cannot alter what the pusher can't see | alice pushes a change; canonical still holds `TOPSECRET.txt` byte-identical |
| Read-only paths reject writes | alice's push touching `README.md` comes back rejected |
| Two collaborators can't disturb each other | bob rewrites `backend/`; alice's `frontend/` is untouched |
| A view repairs a missing `alternates` link | the link is deleted mid-test; the next clone still succeeds |

**Engine-level** — `ViewMaterializerTest` covers filtering, determinism (the same view of the same
history yields identical object IDs, and re-materializing is a no-op), metadata preservation, the
empty-view case, and deep history. `PushReconcilerTest` covers hidden-path preservation, write-ACL
rejection for both read-only edits and injection into invisible directories, correctly scoped
deletions, and branch-deletion refusal. `AclServiceTest` covers the resolution precedence in §5,
including the same user getting different access on different repos. `GlobMatcherTest` covers the
matching rules every decision rests on.

The engine tests build real repositories with JGit rather than mocking it, so they exercise the
actual object model.

---

## 9. Technology choices

| Concern | Choice | Why |
|---|---|---|
| Git plumbing | **JGit** | Pure-Java read/write of objects, trees, refs, and smart-HTTP — lets the rewrite happen in-process, no shelling out to `git`. |
| Server | **Spring Boot** | Smart-HTTP endpoints, the REST API, auth filter, and DI in one familiar runtime. |
| Storage (metadata) | **H2 + JPA** | Users, repos, memberships, path scopes, hashed tokens, audit log. Embedded; zero-setup demo. |
| Object storage | **bare repos + `alternates`** | Canonical holds all blobs once; views reference them instead of duplicating. |
| Auth | **HTTP Basic over hashed tokens** | Works with stock `git` clients; tokens stored SHA‑256, never in plaintext. |
| Dashboard | **React + Vite + TS** | GitHub-Primer-style UI, built into the jar's static resources. |

---

## 10. Where to look in the code

| You want to understand… | Start here |
|---|---|
| Read filtering (the rewrite) | `git/ViewMaterializer.java` |
| Push mapping (the merge-back) | `git/PushReconciler.java` |
| Who-can-see-what resolution | `acl/AclService.java`, `acl/ReadView.java` |
| Glob matching | `acl/GlobMatcher.java` |
| Smart-HTTP wiring | `git/GitHttpController.java`, `git/RepositoryService.java` |
| View storage + `alternates` | `git/ViewService.java` |
| Auth + anonymous tier | `auth/BasicAuthFilter.java`, `auth/AnonymousRateLimiter.java` |
| Web code browser (view-scoped) | `api/BrowseService.java` |
| **What the guarantees actually mean** | `src/test/java/dev/gitgate/GitOverHttpTest.java` |

See [`../PLAN.md`](../PLAN.md) for the full roadmap and design history.
