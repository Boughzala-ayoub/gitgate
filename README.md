# GitGate

[![CI](https://github.com/Boughzala-ayoub/gitgate/actions/workflows/ci.yml/badge.svg)](https://github.com/Boughzala-ayoub/gitgate/actions/workflows/ci.yml)
![License: BSL 1.1](https://img.shields.io/badge/license-BSL%201.1%20(source--available)-blue)

> **Git can't hide files. GitGate can.**

A self-hostable Git server (**Java 21 · Spring Boot · JGit · React**) that enforces **per-user, path-level read/write
permissions** — server-side, over the wire. Each user clones the *same* repository and receives
a deterministically rewritten branch history containing **only the files their read ACL allows**, while pushes are
reconciled back into one canonical repository with out-of-scope writes rejected. *(Source-available under [BSL 1.1](#license), converting to Apache 2.0 after 4 years.)*

<p align="center">
  <img src="docs/demo.webp" alt="GitGate web UI: per-user path-level ACL tree editor, whole-repo exclusions, and filtered repository view" width="900" />
</p>

**Elevator pitch:** *GitHub, but with file-level permissions.* Git's data model makes it
impossible to clone part of a repo — if you can read it, you get all of it. GitGate rewrites
history per-user so one canonical repo can serve many filtered views: give a contractor write
access to `frontend/` only, or make `docs/` public while `src/` stays private — something
GitHub and GitLab structurally cannot do.

---

## The one thing to see

The same clone URL, three different users, three different file sets — enforced by the server:

```
repo "app" on the server:  frontend/  backend/  README.md  TOPSECRET.txt
                           ─────────────────────────────────────────────
  git clone … app.git  as  alice  →   frontend/            README.md
  git clone … app.git  as  bob    →              backend/  README.md
  git clone … app.git  as  admin  →   frontend/  backend/  README.md  TOPSECRET.txt
  git clone … app.git  anonymous  →   (only the repo's public paths, or 401 if private)
```

This is real, not a checkout trick: inside alice's clone, `git cat-file` **cannot retrieve a
hidden blob even by its exact SHA** — the object was never transmitted.

---

## Why it matters

Git is a Merkle DAG: a commit hashes its tree, which hashes its blobs. Cloning gives you every
object in history — there is no native way to share *part* of a repo. That gap is real and
expensive:

- **GitHub / GitLab** offer only repo-level permissions. File-level access is one of their
  most-requested, never-built features.
- **Perforce / Helix** is bought by whole industries (games, film) largely *because* it has
  path-level ACLs — they pay to escape this exact Git limitation.
- Mixed-sensitivity monorepos, contractor access, and secrets-in-history are constant pain.

GitGate closes the gap for Git: one canonical repo, many per-user filtered views. The hard part
— deterministically rewriting history per user and mapping pushes back — is done and verified.
See **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** for how.

## GitGate vs GitHub/GitLab

| Capability | GitHub / GitLab | GitGate |
|---|:---:|:---:|
| Repo-level public/private | ✅ | ✅ |
| Per-collaborator **path** access (write only `frontend/`) | ❌ | ✅ |
| **Partially-public** repos (expose `docs/`, hide `src/`) | ❌ | ✅ |
| Forbidden objects never sent over the wire | ❌ | ✅ |
| Works with stock `git` clients (no plugin) | ✅ | ✅ |

---

## Quickstart

### Docker

```bash
docker compose up -d --build            # http://localhost:8080
docker compose watch                    # …and rebuild automatically when you edit source
```

`docker compose watch` reacts to saves in `src/`, `frontend/src/` and `pom.xml`: it rebuilds the
image and restarts the container, taking roughly **45 seconds** end to end. The build is staged so
a Java-only change reuses the cached `npm ci` and dashboard build, and a dashboard-only change
reuses the cached Maven dependencies.

> A container serves the code that was baked into its image. Editing files on your machine changes
> nothing until the image is rebuilt — that is what `watch` automates. For rapid dashboard work,
> skip Docker and use the dev server (see below), which updates in milliseconds.

Set `GITGATE_PORT` to publish somewhere other than 8080, and `GITGATE_ADMIN_TOKEN` before exposing
the server anywhere. Plain `docker build`/`docker run` still work if you prefer them.

### From source

**Requirements:** JDK 21 · Maven 3.9+ · Git (as a client, to try it)

```bash
mvn clean package                                # also builds the dashboard (Node is fetched for you)
java -jar target/gitgate-0.1.0-SNAPSHOT.jar      # serves http://localhost:8080
```

Open the **dashboard** at `http://localhost:8080/` and sign in as `admin` / `admin123`.
Bare repositories are created on first push under `./data/repos/`.

### Configuration

Everything below can be set in `src/main/resources/application.yml` or as an environment
variable:

| Setting | Env var | Default | What it does |
|---|---|---|---|
| `gitgate.data-dir` | `GITGATE_DATA_DIR` | `./data` | Database, canonical repos and views |
| `gitgate.admin-token` | `GITGATE_ADMIN_TOKEN` | *(demo default)* | Token seeded for the superadmin on first run |
| `server.port` | `SERVER_PORT` | `8080` | HTTP port |

### Seeded users / tokens

| user  | token (Basic-auth password) | access |
|-------|------------------------------|--------|
| admin | admin123                     | everything (superadmin) |
| alice | alice123                     | read+write `frontend`, read `*.md` |
| bob   | bob123                       | read+write `backend`,  read `*.md` |

> Seeded from `src/main/resources/acl.yml` into H2 on first run. Tokens are stored hashed
> (SHA-256); the raw values exist only for the demo. Mint real random tokens from the dashboard.
>
> **Set `GITGATE_ADMIN_TOKEN` on any server you expose** — otherwise the superadmin is seeded with
> the documented `admin123`, and the server logs a warning saying so.

---

## See it yourself

```bash
# As admin, push a repo containing frontend/, backend/, README.md, TOPSECRET.txt …
# then clone the SAME url as three users and compare what arrives:
git clone http://alice:alice123@localhost:8080/app.git   # → frontend/ + README.md only
git clone http://bob:bob123@localhost:8080/app.git       # → backend/  + README.md only
git clone http://admin:admin123@localhost:8080/app.git   # → everything

# Proof it's enforced, not just unchecked-out — a hidden blob can't be fetched by SHA:
git -C app cat-file -t <backend-blob-sha>                # fatal: could not get object info

# Anonymous clone of a partially-public repo returns ONLY the public paths:
git clone http://localhost:8080/app.git                  # → public paths only (or 401 if private)

# On a PUBLIC repo, use the /a/ path to clone as yourself:
git clone http://alice@localhost:8080/a/app.git          # → alice's view, not the public one
```

> **Why `/a/`**: git only sends credentials after a 401, and a public repo never issues one — so a
> plain clone of a public repo is anonymous even with a username in the URL, and a collaborator
> would silently receive the smaller public view. The `/a/` path always challenges. Private repos
> challenge either way, so the prefix only matters for public ones.

`scripts/demo-phase1.ps1` runs the full read-filtering demo end-to-end on Windows.

## Tests

```bash
mvn test                            # everything (31 tests)
mvn test -Dskip.frontend=true       # skip the dashboard build — faster loop
mvn test -Dtest=GitOverHttpTest     # one class
mvn test -Dtest='PushReconcilerTest#pushKeepsHiddenPathsIntact'   # one method
```

Failures print to the console; full reports land in `target/surefire-reports/`. The suite is
re-runnable without `mvn clean`.

> **PowerShell**: quote `-D` arguments — `mvn test "-Dskip.frontend=true"`. Unquoted, PowerShell
> splits it at the dot and Maven reports `Unknown lifecycle phase ".frontend=true"`.

The suite runs the same story against the real server: `GitOverHttpTest` starts GitGate on a
random port and drives **actual git clients** over HTTP — alice, bob and admin clone one URL and
receive three different file sets, a hidden blob is absent from alice's clone *by its exact SHA*,
her push is merged back with `TOPSECRET.txt` untouched, and her push to a read-only path is
rejected. Alongside it, unit tests cover glob matching, deterministic history rewriting
(including that a 2 000-commit history doesn't exhaust the stack), and the push reconciler's
guarantees.

---

## How it works (in three sentences)

A filtered view is a *different commit graph* — removing a path changes every downstream hash —
so GitGate **deterministically rewrites** canonical history into a per-user graph containing only
readable paths (blobs are shared via `alternates`, never copied). Determinism is what lets
`git fetch` stay incremental: the same view always yields the same object IDs. On push, a
**merge-based reconciler** rebuilds canonical commits by taking readable paths from the user and
carrying every hidden path over untouched — so a push *structurally cannot* alter files the user
can't see.

Full detail, diagrams, and the threat model: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

---

## What's built

A working multi-tenant Git platform, not a proof of concept.

**The engine**

- **Per-user filtered clones** (reads) and **reconciled pushes with path-level write-ACL** (writes).
- History rewriting is **deterministic** (so `fetch` stays incremental) and **iterative**, so a
  repository with deep history doesn't exhaust the JVM stack on clone or first push.
- Views share canonical blobs through `alternates` and repair that link if it goes missing, rather
  than silently serving unreadable packs.

**The platform**

- **Multi-tenant**: repos have an owner + collaborators; access is keyed by `(user, repo)` with
  per-collaborator path scopes.
- **Accounts & self-service**: public signup/login; create your own repos; scope collaborators.
- **Point-and-click permissions**: repo settings show the actual file tree — grant a person
  read or write on a folder or a single file by clicking it, and see at a glance which paths are
  covered by a parent folder or a pattern.
- **Exclusions**: any grant can have paths carved back out of it, and an exclusion always beats a
  grant. That covers the two cases enumerating globs can't:
  - *"public, except these files"* — a repo anyone may clone, with paths that never leave the server.
  - *"this folder, except that file"* — a contractor with write access to `backend/` but no sight
    of `backend/secrets.env`.
- **Public & anonymous access**, including **partially-public repos** (public path globs).
- **In-browser code browser** served strictly from the viewer's filtered view (access control is
  automatic — a hidden file 404s even by direct URL).
- **Explore** tab for discovering public repos.
- Persistence in **H2/JPA**, **hashed-token** auth, an audit log, and a **React (Vite) dashboard**
  served by the jar.

**Operability**

- `mvn verify` is the whole gate: 31 tests plus the dashboard build, run on every push by
  [CI](.github/workflows/ci.yml).
- `docker build` produces a runnable image; all persistent state lives under one `GITGATE_DATA_DIR`.
- The seeded superadmin token is overridable, and the server warns when the demo default is in use.

The access-control guarantees are covered by automated tests rather than by manual demos — see
[Tests](#tests) above. [`PLAN.md`](PLAN.md) has the roadmap and design history.

<details>
<summary><strong>How it was built (phases 0–9)</strong></summary>

- **Phase 0 — Git smart-HTTP server.** clone / fetch / push over HTTP with Basic auth.
- **Phase 1 — Per-user filtered clones.** Each user receives only the paths their ACL grants;
  forbidden objects are never sent (verified: a user cannot retrieve a hidden blob even by its
  SHA). History is rewritten deterministically so `fetch` works incrementally.
- **Phase 2 — Reconciled push + write-ACL.** A user pushes against their filtered view; the
  server merges their changes back onto canonical history, preserving every hidden path, and
  rejects writes to paths they lack `write` on. Verified: alice edits `frontend/` (canonical
  keeps `backend/`+`TOPSECRET` intact), her edits to read-only `README.md` and an injected
  `backend/evil.js` are both rejected, and alice & bob each push their own half of one repo
  without ever seeing the other's.
- **Phase 3 — Persistence + admin dashboard.** Users, roles, ACL rules, tokens, and an audit log
  in embedded **H2** (JPA). Auth is **hashed personal access tokens** (`acl.yml` seeds the DB on
  first run). A secured admin REST API (`/api/**`) drives a **React (Vite) dashboard** at
  `http://localhost:8080/`.
- **Phase 6 — Multi-tenant access (per-repo).** Access keyed by `(user, repo)`: repos have an
  owner and collaborators, each membership narrowable by per-collaborator path scopes. A
  `superadmin` has full access; pushing a new name creates the repo (pusher = owner). Verified:
  the same user gets different access on different repos, non-members see nothing, write-ACL is
  per-repo, and push-to-create assigns ownership.
- **Phase 7 — Accounts & self-service.** Public signup/login; authorization rework
  (`/api/auth/**` public, `/api/admin/**` super-admin, other `/api/**` authenticated with
  per-resource checks); user-scoped repo + collaborator API. Verified: a brand-new self-signed-up
  user created a repo, scoped another user to `docs/`, and that user's clone contained only `docs/`.
- **Phase 8 — Public & anonymous access (incl. partially-public repos).** A repo has a visibility
  (PRIVATE/PUBLIC) and, when public, public path globs. The auth filter permits anonymous reads of
  public repos through a read-only view while writes/private repos require auth; a per-IP rate
  limiter caps anonymous traffic. Verified: an anonymous clone of a partially-public repo returns
  only the public paths (`frontend` + `*.md`, not `backend/`/`TOPSECRET`), a private repo returns
  401, and anonymous push is rejected.
- **Phase 9 — In-browser code browser.** A read-only browse API served strictly from the viewer's
  materialized view, with a React file browser (branch selector, breadcrumbs, file viewer).
  Anonymous browsing allowed for public repos (rate-limited). Verified: owner / scoped-collaborator
  / anonymous each see only their paths, and a hidden file returns 404 even by direct URL.
- **Hardening.** The phase demos above were manual; they are now automated tests. Two defects
  surfaced in the process and were fixed: history rewriting recursed per commit (any repo with a
  few thousand commits overflowed the stack on clone or first push), and a view repository that
  lost its `alternates` link never regained it, serving broken packs indefinitely. Packaging,
  Docker, CI and the admin-token override landed alongside.

</details>

---

## Limitations (by design)

Stating the boundary precisely is part of the security story:

- A malicious **server/admin** is trusted — they hold the full content by definition.
- **Already-cloned data can't be revoked** — bytes a user received are theirs; ACL changes apply
  going forward only. Inherent to any non-DRM system.
- **Side channels**: a commit *message* or an allowed file may reference a hidden path.
- **Signed commits** can't survive rewriting — signatures break when trees/parents change.

---

## Tech stack

**Java 21 · Spring Boot · JGit · H2/JPA** on the server; **React + Vite + TypeScript** dashboard.
Auth is HTTP Basic over hashed tokens, so it works with stock `git` clients — no plugin.

## Project layout

```
src/main/java/dev/gitgate/
  git/    GitHttpController, ViewMaterializer (read rewrite), PushReconciler (write merge), RepositoryService
  acl/    AclService (who-sees-what), ReadView, GlobMatcher
  api/    REST controllers: repos, collaborators, visibility, browse, explore, admin
  auth/   BasicAuthFilter, AnonymousRateLimiter, token hashing
  store/  JPA entities + repositories (users, repos, memberships, scopes, tokens, audit)
src/test/java/dev/gitgate/
  GitOverHttpTest — real git clients against the running server; the rest are engine unit tests
frontend/ React + Vite dashboard (built into src/main/resources/static/ by the Maven build)
docs/     ARCHITECTURE.md
Dockerfile, .github/workflows/ci.yml
```

See [docs/ARCHITECTURE.md §10](docs/ARCHITECTURE.md#10-where-to-look-in-the-code) for a guided tour.

---

## Frontend (dashboard) development

The dashboard is a **Vite + React + TypeScript** app in `frontend/`, styled to match GitHub's
Primer design. `mvn package` builds it into `src/main/resources/static/` and bundles it into the
jar — Maven downloads its own Node, so a fresh clone needs nothing installed. Pass
`-Dskip.frontend=true` to skip that step and build against whatever is already there.

```bash
cd frontend
npm install
npm run dev      # hot-reload dev server on :5173, proxies /api to :8080
npm run build    # rebuild into src/main/resources/static/, then repackage the jar

# point the dev server at a backend running somewhere else (e.g. a container on 8100)
VITE_API_TARGET=http://localhost:8100 npm run dev
```

The dev server is the fast loop for dashboard work: changes appear immediately, against whichever
backend you point it at. Docker rebuilds (~45s) are for verifying the packaged artifact.

> The running jar serves the frontend **bundled at package time** — editing `frontend/src` and
> running `npm run build` is not enough to change `:8080`; you must repackage the jar
> (`mvn -DskipTests package`) and restart it, or develop against `npm run dev` on `:5173`.

### Responsive testing

The dashboard is mobile-first and must render with no horizontal overflow from **320px up**. A
headless check drives the real app, logs in, visits every view at several widths, asserts
`scrollWidth <= clientWidth`, and writes a screenshot per view/width:

```bash
cd frontend && npm run check:responsive          # against http://localhost:8080
```

It exits non-zero on overflow (so it can gate a build). Env: `RESP_BASE`, `RESP_WIDTHS`,
`RESP_USER`/`RESP_PASS`, `CHROME`. Tables collapse to labelled cards below 600px; the header
collapses to a hamburger below 768px; inputs go full-width on phones. The check also opens the
permission picker and expands its tree — the interactive states are where overflow hides.

```bash
cd frontend && npm run check:paths        # browser path-matching agrees with the server
```

`src/paths.ts` mirrors the server's `GlobMatcher` so the settings UI can explain *why* a file is
accessible (“via `*.md`”). If the two drift the UI mislabels access, so the same cases as the Java
`GlobMatcherTest` are asserted here.

---

## License

GitGate is **source-available** under the [Business Source License 1.1](LICENSE) — the license
MariaDB, HashiCorp and Couchbase use.

- **Free**: reading the source, evaluating it, developing and testing against it, modifying and
  redistributing it — any non-production use.
- **Paid**: running GitGate in production requires a commercial license.
  Contact **ayoubboughzalaprep23@gmail.com**.
- **On 2030-07-28** this version converts automatically to **Apache-2.0**.

It is not an OSI open-source license. Dependencies keep their own licenses (JGit — BSD/EDL,
Spring Boot — Apache-2.0, H2 — EPL/MPL).

---

## Notes for Windows / JDK discovery

If `mvn` complains about `JAVA_HOME`:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
```

`scripts/run-server.ps1` auto-detects JDK 21 and runs the jar.
