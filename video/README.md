# GitGate launch video

A 60-second, silent launch clip built with [Remotion](https://remotion.dev) — React components
rendered to video, so the whole thing is source-controlled and re-renderable.

```bash
cd video
npm install
npm run studio      # live editor at :3000, scrub the timeline
npm run render      # → out/gitgate.mp4  (1920×1080, 30fps, ~4 MB)
```

Renders land in `out/`, which is gitignored — the source is the artifact here, not the MP4.

## The cut

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
