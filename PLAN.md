# GitGate — File-Level Access Control for Git

> A self-hostable Git server (Spring Boot + JGit) that enforces **per-user, path-level READ and WRITE access control**. Unauthorized users never receive forbidden file blobs over the wire — security is enforced at the server, not faked on the client.

---

## 0. Status & product direction

**Built so far (Phases 0–3, complete & verified):** a working Git server with per-user filtered
clones (reads), reconciled pushes with path-level write-ACL (writes), H2/JPA persistence,
hashed-token auth, an admin REST API, and a React dashboard.

**New direction:** evolve the single-tenant engine into a **multi-tenant platform** — *"GitHub,
but with file-level permissions."* The novel part (per-user view materialization + push
reconciliation) is the moat and is already done; everything that follows is a standard SaaS layer
(ownership, self-service, public/anonymous access, a web code browser) wrapped around the
**unchanged** engine.

**Differentiators vs GitHub (only possible because of the engine):**
- **Path-level collaborator access** — give a contractor write to only `frontend/`.
- **Partially-public repos** — expose `docs/` to the world while `src/` stays private. GitHub can
  only do all-public or all-private.

---

## 1. The problem & why it matters

Git cannot hide files. Its data model is a Merkle DAG: a commit hashes its tree, which hashes its subtrees and blobs. If you can `clone` a repo, you receive **every object in history** — there is no native way to give someone part of a repo.

This gap is expensive and real:
- **GitHub/GitLab** offer only repo-level permissions. File-level permissions is one of the most-requested, never-built features.
- **Perforce/Helix** exists largely *because* it has path-level ACLs — entire industries (gaming, film) pay for it to escape this exact Git limitation.
- Mixed-sensitivity monorepos, contractor access, and secrets-in-repos are constant pain points.

**GitGate** closes the gap for Git: one canonical repo, many per-user filtered views, enforced server-side.

### The central technical challenge
Removing a path from a tree changes that tree’s hash → changes the commit hash → changes the entire downstream history. So a filtered view is a **different commit graph** than the canonical repo. The whole project is about generating those per-user graphs correctly, deterministically, and reversibly (so pushes can be mapped back).

---

## 2. Threat model (read this before designing — it defines “correct”)

**Trusted:** the server and its administrators. They hold the full canonical repo with all content.

**Protected against:**
- An authenticated user **reading** paths outside their ACL (forbidden objects are never sent over the wire — not just hidden in the working tree).
- An authenticated user **writing** paths outside their write ACL.
- Leakage of hidden file **existence/names** (filtered trees omit entries entirely, not just contents).

**NOT protected against (documented limitations — stating these is a maturity signal):**
- A malicious server/admin (they have everything by definition).
- **Revocation of already-cloned data** — anyone who once had read access keeps the bytes they already cloned. ACL changes apply going forward only. *Inherent to any non-DRM system.*
- Side-channel leaks: a commit **message** or an allowed file may reference a hidden path.
- Traffic analysis (sizes/timing).
- Signed commits cannot survive rewriting (signatures break when trees/parents change).

---

## 3. Architecture

```
                          ┌─────────────────────────────────────────┐
   git clone/push  ─────► │  Git Smart-HTTP endpoint (Spring Boot)   │
   (HTTP Basic/token)     │  /info/refs, git-upload-pack,            │
                          │  git-receive-pack                        │
                          └───────────────┬─────────────────────────┘
                                          │
                    ┌─────────────────────┼──────────────────────┐
                    ▼                     ▼                      ▼
            ┌──────────────┐     ┌────────────────┐     ┌────────────────┐
            │ Auth filter  │     │  ACL engine    │     │ Audit logger   │
            │ user+roles   │     │ user→path glob │     │                │
            └──────┬───────┘     └───────┬────────┘     └────────────────┘
                   │                     │
                   ▼                     ▼
            ┌────────────────────────────────────────┐
            │  View materializer (the hard part)      │
            │  canonical graph + ACL → filtered graph │
            │  incremental + cached + deterministic   │
            └───────┬──────────────────────┬──────────┘
                    │ reads                 │ writes (push reconciler)
                    ▼                       ▼
        ┌────────────────────┐   ┌──────────────────────────────┐
        │ Canonical repos    │   │ Mapping store (Postgres)      │
        │ (bare JGit, full)  │   │ (canonicalCommit,view)↔view   │
        └────────────────────┘   └──────────────────────────────┘

   ┌──────────────────────────────────────────────────────────────┐
   │ React Admin Dashboard → REST API: repos, users, roles,        │
   │ ACL rules, tokens, audit log                                  │
   └──────────────────────────────────────────────────────────────┘
```

### Components
1. **Git Smart-HTTP endpoint** — Spring Boot controller wiring JGit `UploadPack` (clone/fetch) and `ReceivePack` (push) to a *per-user view repository* instead of the canonical one.
2. **Auth filter** — HTTP Basic / personal access token (Git supports `user:token`). Resolves identity + roles.
3. **ACL engine** — resolves a user to a path matcher (gitignore-style globs) for read and write.
4. **View materializer** — core engine. Given (canonical repo, ACL view), produces a filtered commit graph: rewrites each tree to omit forbidden paths, rewrites parent pointers, preserves author/committer/message/timestamps. Deterministic (same view ⇒ same hashes) and **cached** so fetches are incremental.
5. **Canonical repo store** — bare JGit repositories on disk; the full-content source of truth.
6. **Mapping store (Postgres)** — persistent `(canonicalCommitId, viewId) ↔ viewCommitId`, used for incremental views and for translating pushes back to canonical.
7. **Push reconciler** — maps a user’s view-push back onto canonical history: re-inserts untouched forbidden subtrees from the canonical parent, enforces write ACL, updates the canonical ref.
8. **Admin dashboard (React)** — manage repos/users/roles/ACL/tokens, view audit log. Makes it demoable and portfolio-ready.

### Tech stack (all free)
- **Java 21**, **Spring Boot 3.x**
- **Eclipse JGit** — pure-Java Git: smart-HTTP protocol, object/tree manipulation, pack building. This is the linchpin that makes the project buildable and free.
- **Postgres** (ACL, users, mappings, audit). H2 for zero-dependency local dev.
- **React + Vite** admin UI.
- **Docker / docker-compose** for one-command run.
- **JUnit + Testcontainers**, with the real `git` CLI as the integration test client.

---

## 4. Data model (Postgres)

```
users(id, username, created_at)
tokens(id, user_id, token_hash, label, created_at, last_used_at)
roles(id, name)
user_roles(user_id, role_id)
repos(id, name, canonical_path, created_at)
acl_rules(id, repo_id, role_id, path_glob, can_read, can_write, priority)
commit_mappings(repo_id, view_id, canonical_commit, view_commit)      -- read direction
                PRIMARY KEY (repo_id, view_id, canonical_commit)
push_mappings(repo_id, view_id, view_commit, canonical_commit)        -- write direction
audit_log(id, ts, user_id, repo_id, action, ref, detail)
```

- **view_id** = a stable hash of the resolved (sorted) ACL path-rule set for a user on a repo. Users with identical permissions share one view (and its cache) — important optimization.

---

## 5. The view materializer (deepest part — design notes)

**Goal:** a pure, deterministic function `materialize(canonicalCommit, view) → viewCommit`, cached in `commit_mappings`.

Algorithm (depth-first over history, memoized):
1. If `(canonicalCommit, view)` is in `commit_mappings`, return the cached `viewCommit`.
2. Recursively materialize all **parents** → list of rewritten parent ids.
3. **Filter the tree:** walk the canonical commit’s tree; for each entry, keep it only if its path is readable under `view`. Recurse into subtrees; drop subtrees that become empty (or keep an empty marker — see edge cases). Write the new tree objects via JGit.
4. Build a new commit object: filtered tree + rewritten parents + **preserved** author/committer/message/time. Its hash is determined entirely by (filtered content, rewritten parents, preserved metadata) ⇒ deterministic.
5. Store mapping, return.

**Incremental:** on new canonical commits, only the new commits are materialized; the rest hit the cache.

**Determinism across restarts:** guaranteed by persisting `commit_mappings` and by the function being pure. Same view ⇒ identical hashes ⇒ `git fetch` works normally.

### Edge cases (decide explicitly)
| Case | MVP decision | Later optimization |
|---|---|---|
| Commit touches only forbidden paths (becomes empty in view) | Keep as empty commit (preserves graph shape + hash stability) | Optionally prune + re-parent |
| Merge commits | Rewrite all parents | — |
| Branch/tag lives entirely in forbidden area | Filter it out of ref advertisement | — |
| Empty filtered tree at root | Serve an empty repo (user has access to nothing on this repo) | — |
| Signed commits | Drop signature, document it | — |

### Security must-haves
- The pack served to a user must be built **only** from the rewritten graph — assert it references no canonical-only object ids.
- Ref advertisement filtered per view (don’t reveal refs the user can’t see).
- Hidden entries omitted from trees entirely (don’t reveal names/sizes).

---

## 6. The push reconciler (Phase 2 — the hardest part)

When a user pushes view-commits whose parent maps to a known canonical commit:
1. For each new view-commit (oldest first): take the canonical tree of the **mapped parent**, apply the user’s changes **only to readable paths**, leaving all forbidden subtrees exactly as they are in the canonical parent (the user never saw or touched them). Produce the canonical commit.
2. **Enforce write ACL:** reject if the diff touches any path the user lacks `can_write` on.
3. Record `push_mappings` + `commit_mappings` for the new pair.
4. **Concurrency:** if canonical HEAD moved since the user’s base, the forbidden side never conflicts (user didn’t touch it) — auto-carry it; if an *allowed* path conflicts, reject as non-fast-forward and tell the user to pull.

---

## 7. Phased roadmap (~12 weeks solo, part-time-friendly)

### Phase 0 — Foundations *(week 1)*
- Maven project, Spring Boot + JGit deps, GitHub repo, CI (GitHub Actions).
- Serve a **vanilla** (unfiltered) repo over smart-HTTP via JGit. Prove `git clone http://localhost:8080/repo.git` works.
- HTTP Basic auth → user identity.
- **DoD:** can clone/push a normal repo through your server with auth.

### Phase 1 — Read-only filtered clone *(weeks 2–4)* — **the core demo**
- ACL model + engine (roles, users→roles, path globs). Static YAML to start.
- View materializer (Section 5), mappings cached in Postgres.
- Wire `UploadPack` to serve the per-user view.
- **DoD / the money demo:** `alice` and `bob` clone the *same URL* and get *different file sets*; forbidden files are provably absent from `.git`. **Record the GIF here.**
- Tests: clone as each user; assert file presence/absence; assert forbidden object ids never transferred.

### Phase 2 — Push with reconciliation *(weeks 5–7)*
- Write ACL + `ReceivePack` interception + push reconciler (Section 6).
- Reject writes to forbidden paths; handle non-fast-forward.
- **DoD:** a user pushes an allowed change → canonical updates and hidden files stay intact; a forbidden write is rejected.

### Phase 3 — Admin dashboard (React) *(weeks 8–9)*
- REST API + React UI: manage repos, users, roles, ACL rules, tokens; view audit log.
- **DoD:** create a repo, define roles/rules, and mint tokens entirely from the UI.

### Phase 4 — Hardening & performance *(weeks 10–11)*
- Ref/tag filtering, merge/empty-commit handling, large-repo caching, first-clone cost.
- Security pass: object-leak tests, existence-hiding, side-channel docs, rate limiting.
- **DoD:** passes a written security checklist; performs acceptably on a repo with thousands of commits.

### Phase 5 — Polish & launch *(week 12)*
- README + architecture diagram + GIF; live demo (Oracle Always Free / Cloudflare Tunnel); docs; `docker compose up`.
- **Blog post:** *“Why file-level permissions are impossible in Git — and how I built them anyway.”*
- Launch: Show HN, lobste.rs, r/programming, r/java, r/git, r/selfhosted.

---

## 7b. Platform roadmap — from engine to "GitHub with file-level permissions"

> Phases 0–3 are complete. The original **Phase 4 (hardening)** and **Phase 5 (launch)** still
> apply but move to the end of this sequence (now Phases 10 and 12), because multi-tenancy changes
> what must be hardened. Everything below wraps the **existing, unchanged** engine
> (`ViewMaterializer`, `PushReconciler`, smart-HTTP, `GlobMatcher`) — none of the core is rewritten.

### The one architectural change that unlocks everything
Today access is **repo-agnostic**: `AclService.resolve(username)` returns the same view for every
repo. Multi-tenancy makes access **per (user, repo)**: `resolve(username, repo)`. The git engine
just passes the repo through; the source of globs moves from global roles to per-repo membership.
This is the spine of the platform.

### New / extended data model
- **Repo** becomes first-class: `(id, name, owner_id, visibility[private|public], created_at)`.
- **Membership** `(user_id, repo_id, role[admin|write|read])` — who collaborates on a repo.
- **Per-collaborator path scopes** `(membership_id, path_glob, can_read, can_write)` — the
  file-level twist: a collaborator's access can be narrowed to paths *within that repo*.
- **Anonymous principal** — a public repo carries read globs for the pseudo-user `anonymous`
  (`**` for fully public, or a subset for partially public).

### Threat-model addition
`anonymous` is just the lowest-privilege viewer. Public reads are served through the **anonymous
materialized view** — never canonical — so the existing "you can't receive what you can't see"
guarantee holds unchanged. **No anonymous writes, ever.**

### Phase 6 — Multi-tenant access model
- Repo ownership + Membership + per-collaborator path scopes (data model above).
- Make ACL repo-aware: `resolve(user, repo)`; thread `repo` through `ViewService`/controller.
- Scope authorization: API actions check "is the caller an **admin of this repo**?" instead of a
  single global admin.
- **DoD:** two repos with different collaborator sets; one user's view differs per repo.

### Phase 7 — Accounts & self-service
- User sign-up + login (session or token), profile.
- Explicit **create repository** (owned by the creator) replacing global push-to-create.
- Per-user dashboard: manage only repos you own/admin; add collaborators with roles + path scopes.
- **DoD:** a new user signs up, creates a repo, and adds a collaborator scoped to one folder.

### Phase 8 — Public & anonymous access
- `visibility` on repos; unauthenticated git read allowed for public repos via the anonymous view.
- **Partially-public** repos (anonymous read globs) — the headline differentiator.
- Rate-limit anonymous endpoints; never allow anonymous writes.
- **DoD:** anonymous `git clone` of a public repo works and returns only the public paths.

### Phase 9 — In-browser code browser
- Read-only browse API: tree at a ref, blob contents, branches/commits — served **only** from the
  viewer's materialized view (anonymous for public repos).
- React pages: repo home, file tree, file view, commit history. Access guarantees come for free
  from the view engine.
- **DoD:** browse a repo in the browser; private paths never appear, even by direct URL.

### Phase 10 — Scale & production hardening *(absorbs old Phase 4)*
- **Postgres** instead of H2.
- **Persistent, incremental view-materialization cache** (the deferred commit-mapping cache) —
  now necessary: many users × many repos × public traffic cannot re-materialize per request.
- Edge cases (tags, annotated merges, large repos), pagination, rate limiting, observability.
- Later: object/distributed git storage, horizontal scaling.

### Phase 11 — Organizations & teams *(optional, advanced)*
- Org → team → repo-permission hierarchy, GitHub-style, layered on memberships.

### Phase 12 — Launch *(old Phase 5)*
- Architecture diagram, the blog post (*"Why file-level permissions are impossible in Git — and how
  I built them anyway"*), live demo, docs.

---

## 8. Risks & mitigations
| Risk | Likelihood | Mitigation |
|---|---|---|
| View materialization too slow on big repos | High | Cache aggressively; incremental; share views across same-permission users; lazy-materialize on first access |
| Push reconciliation correctness bugs | High | Heavy property-based + CLI integration tests; start read-only and ship that first |
| Scope creep (trying to match Perforce) | Med | Ruthlessly ship read-only MVP first; everything else is post-launch |
| JGit protocol edge cases | Med | Lean on JGit’s `UploadPack`/`ReceivePack`; test against the real git client |
| “Why not just git-crypt/submodules?” objection | Low | README’s comparison table + threat model answers it directly |

---

## 9. Definition of MVP (what to build first, ruthlessly)
Phases 0–1 only: **read-only, per-user filtered clone with a static YAML ACL.** That alone is a complete, demoable, blog-worthy project. Push (Phase 2) and the dashboard (Phase 3) are what turn it from “impressive demo” into “real product” — but do not let them block shipping the MVP.
