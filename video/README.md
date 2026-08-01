# GitGate videos

Silent films built with [Remotion](https://remotion.dev) — React components rendered to video, so
they are source-controlled and re-renderable.

| Composition | Length | What it's for |
|---|---|---|
| `GitGateLaunch` | 60s | Launch clip: the problem, the three-clone reveal, the proof |
| `GitGateMarketing` | 90s | Product film: the real UI, who it's for, and what building it took |

```bash
cd video
npm install
npm run studio             # live editor at :3000, scrub either timeline
npm run render             # → out/gitgate.mp4            (GitGateLaunch)
npm run render:marketing   # → out/gitgate-marketing.mp4  (GitGateMarketing)
```

Renders land in `out/`, which is gitignored — the source is the artifact here, not the MP4.

## The marketing film

Real screenshots of the running dashboard, not mockups. They live in `public/shots/` and are
loaded with `staticFile()`.

| Time | Beat |
|---|---|
| 0:00 | Every Git host gives you one switch — GitGate gives you one per file |
| 0:07 | The repository list: this is a server you log into |
| 0:13 | Settings: one place for who can see what |
| 0:20 | Permissions by clicking the actual file tree |
| 0:29 | Grant a folder, keep one file out |
| 0:39 | Or everything, minus the secrets |
| 0:47 | Usable on a phone |
| 0:53 | What it's for: contractor, open project, monorepo |
| 1:03 | What it took: the five hard parts |
| 1:17 | Verified, not asserted: 43 tests, 5.2k lines, 320px |
| 1:23 | Title card |

### Refreshing the screenshots

`public/shots/` is a committed copy, because the source directory is gitignored and would be
missing from a fresh clone. To update it after a UI change:

```bash
cd ../frontend && npm run check:responsive      # regenerates responsive-shots/
cp responsive-shots/settings-*-1280.png ../video/public/shots/    # and the others you use
```

The `Shot` component pans over a full-page capture using `topFrom`/`topTo` in the PNG's **own
pixels** — measure the region off the screenshot rather than guessing a fraction. If a capture
changes height, re-check the framing with `npx remotion still GitGateMarketing out/x.png --frame=N`.

## The launch clip cut

| Time | Beat |
|---|---|
| 0:00 | "Git can't hide files." — the problem, stated flat |
| 0:06 | One repository, one clone URL |
| 0:14 | The reveal: alice, bob and admin run the same command, get different files |
| 0:24 | "Same URL. Same repository. **Different bytes.**" |
| 0:32 | The proof: `git cat-file` on the hidden blob's exact SHA → `fatal` |
| 0:44 | How: canonical history rewritten per user, deterministically |
| 0:54 | Title card |

## Notes

- Timings live in `src/GitGateLaunch.tsx` at the bottom — each `<Sequence>` is one beat, in frames
  at 30fps. Change one and everything after it shifts.
- Colours are GitHub's dark palette (`src/theme.tsx`), matching the Primer-styled dashboard.
- The SHA shown in the proof scene is the real blob id of `TOPSECRET.txt` from the test fixture.
- Fonts are system stacks — no downloads, so a render on a clean machine looks the same.

Remotion is free for individuals and small companies; a company licence is required above their
headcount threshold. See <https://remotion.dev/license>.
