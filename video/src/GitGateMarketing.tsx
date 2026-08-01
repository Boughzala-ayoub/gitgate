import React from 'react';
import {
  AbsoluteFill, Img, Sequence, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig,
} from 'remotion';
import {C, MONO, SANS, fade, fadeInOut} from './theme';
import {Caption, Shield} from './GitGateLaunch';

/* ------------------------------------------------------------------ screenshot framing */

/**
 * A real dashboard screenshot in a browser frame.
 *
 * The captures are full-page, so they are far taller than the video is — scaling one to fit would
 * make it unreadable. Instead the image fills the frame's width and the frame pans down it, which
 * is also how a person would actually read the page.
 */
const Shot: React.FC<{
  src: string;
  /** Natural size of the PNG, so the pan can be computed rather than guessed. */
  w: number;
  h: number;
  /** Which part of the page to show, in the PNG's own pixels: the y that sits at the top of the
   *  frame at the start and at the end. Real pixels because that is what you can measure off the
   *  screenshot; fractions of an unknown overflow are guesswork. */
  topFrom?: number;
  topTo?: number;
  /** Browser chrome for desktop captures, a phone shell for the narrow ones. */
  chrome?: 'browser' | 'phone';
  /** Frames this shot is on screen for; drives the pan. */
  frames: number;
  width?: number;
  height?: number;
  label?: string;
}> = ({
  src, w, h, topFrom = 0, topTo = 0, frames,
  width = 1240, height = 660, label = 'gitgate', chrome = 'browser',
}) => {
  const frame = useCurrentFrame();
  const scale = width / w;
  const overflow = Math.max(0, h * scale - height);
  const clamp = (px: number) => Math.min(Math.max(px * scale, 0), overflow);
  const progress = interpolate(frame, [0, frames], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const offset = interpolate(progress, [0, 1], [clamp(topFrom), clamp(topTo)]);
  const enter = fade(frame, 2, 14);

  return (
    <div
      style={{
        width,
        borderRadius: chrome === 'phone' ? 30 : 12,
        overflow: 'hidden',
        border: chrome === 'phone' ? `8px solid #2b3138` : `1px solid ${C.border}`,
        boxShadow: '0 30px 70px rgba(0,0,0,0.55)',
        opacity: enter,
        transform: `translateY(${interpolate(enter, [0, 1], [18, 0])}px)`,
      }}
    >
      {chrome === 'browser' ? (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: '10px 14px',
            background: '#1b2129',
            borderBottom: `1px solid ${C.border}`,
          }}
        >
          <span style={{width: 10, height: 10, borderRadius: 5, background: '#ff5f57'}} />
          <span style={{width: 10, height: 10, borderRadius: 5, background: '#febc2e'}} />
          <span style={{width: 10, height: 10, borderRadius: 5, background: '#28c840'}} />
          <span style={{fontFamily: MONO, fontSize: 15, color: C.muted, marginLeft: 12}}>{label}</span>
        </div>
      ) : null}
      <div style={{height, overflow: 'hidden', background: '#fff'}}>
        <Img src={staticFile(src)} style={{width, display: 'block', transform: `translateY(${-offset}px)`}} />
      </div>
    </div>
  );
};

/** Caption above a shot, so the viewer knows what they're looking at before they look. */
const Titled: React.FC<{title: string; sub?: string; children: React.ReactNode}> = ({title, sub, children}) => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{justifyContent: 'center', alignItems: 'center', gap: 26}}>
      <div style={{textAlign: 'center', opacity: fade(frame, 0, 12)}}>
        <div style={{fontFamily: SANS, fontSize: 46, fontWeight: 700, color: C.text}}>{title}</div>
        {sub && <Caption style={{fontSize: 27, marginTop: 8}}>{sub}</Caption>}
      </div>
      {children}
    </AbsoluteFill>
  );
};

/* ------------------------------------------------------------------ scenes */

// 0:00 — the gap every Git host leaves.
const Hook: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{justifyContent: 'center', alignItems: 'center', gap: 30}}>
      <div
        style={{
          fontFamily: SANS, fontSize: 62, fontWeight: 600, color: C.muted,
          opacity: fadeInOut(frame, 6, 140),
        }}
      >
        Every Git host gives you one switch.
      </div>
      <div
        style={{
          fontFamily: MONO, fontSize: 44, color: C.text,
          opacity: fadeInOut(frame, 34, 112),
        }}
      >
        <span style={{color: C.muted}}>repository:</span> public <span style={{color: C.muted}}>or</span> private
      </div>
      <div
        style={{
          fontFamily: SANS, fontSize: 76, fontWeight: 800, color: C.green,
          opacity: fade(frame, 108),
          transform: `scale(${interpolate(fade(frame, 108, 16), [0, 1], [0.92, 1])})`,
          marginTop: 10,
        }}
      >
        GitGate gives you one per file.
      </div>
    </AbsoluteFill>
  );
};

// 0:07 — this is a real product, here it is.
const Repos: React.FC<{frames: number}> = ({frames}) => (
  <Titled title="A Git server you actually log into" sub="Your repositories, public and private">
    <Shot src="shots/repos-1280.png" w={1280} h={900} frames={frames} height={620} label="gitgate — repositories" />
  </Titled>
);

// 0:13 — one place to answer "who can see what".
const SettingsOverview: React.FC<{frames: number}> = ({frames}) => (
  <Titled title="One place for who can see what" sub="Visibility, people, and the paths each of them gets">
    <Shot
      src="shots/repo-settings-1280.png" w={1280} h={1324} frames={frames}
      topFrom={40} topTo={700} height={620} label="gitgate — settings"
    />
  </Titled>
);

// 0:20 — the idea that makes it different.
const TreePicker: React.FC<{frames: number}> = ({frames}) => (
  <Titled title="Permissions by clicking the files" sub="The real repository tree — no glob syntax to learn">
    <Shot
      src="shots/settings-tree-expanded-1280.png" w={1280} h={2103} frames={frames}
      topFrom={1240} topTo={1530} height={620} label="gitgate — add people"
    />
  </Titled>
);

// 0:29 — grant, then carve back out.
const Exclusion: React.FC<{frames: number}> = ({frames}) => {
  const frame = useCurrentFrame();
  return (
    <Titled title="Grant a folder. Keep one file out." sub="An exclusion always wins — no broader rule can undo it">
      <Shot
        src="shots/settings-exclusion-1280.png" w={1280} h={2103} frames={frames}
        topFrom={1230} topTo={1560} height={560} label="gitgate — access"
      />
      <Caption style={{fontSize: 26, opacity: fade(frame, 200), color: C.green}}>
        backend/ read + write · backend/secrets.env hidden
      </Caption>
    </Titled>
  );
};

// 0:39 — the shape people ask for most.
const WholeRepoExcept: React.FC<{frames: number}> = ({frames}) => (
  <Titled title="Or everything, minus the secrets" sub="Entire repository, then hide the odd file or folder">
    <Shot
      src="shots/settings-whole-repo-except-1280.png" w={1280} h={2084} frames={frames}
      topFrom={1090} topTo={1430} height={600} label="gitgate — entire repository"
    />
  </Titled>
);

// 0:47 — it isn't a desktop-only admin panel.
const Phone: React.FC<{frames: number}> = ({frames}) => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{justifyContent: 'center', alignItems: 'center', flexDirection: 'row', gap: 90}}>
      <div style={{maxWidth: 640, opacity: fade(frame, 4)}}>
        <div style={{fontFamily: SANS, fontSize: 46, fontWeight: 700, color: C.text, marginBottom: 14}}>
          Usable on a phone
        </div>
        <Caption style={{fontSize: 27, textAlign: 'left'}}>
          Every view reshapes down to 320px — the tree, the access controls and the destructive
          actions all stay reachable. Checked automatically at five widths on every change.
        </Caption>
      </div>
      <Shot
        src="shots/settings-tree-expanded-375.png" w={375} h={3930} frames={frames}
        topFrom={2180} topTo={2620} width={420} height={720} chrome="phone"
      />
    </AbsoluteFill>
  );
};

// 0:53 — who actually needs this.
const UseCases: React.FC = () => {
  const frame = useCurrentFrame();
  const cases = [
    {
      who: 'The contractor',
      what: 'Write access to frontend/ — and no sight of the billing code, ever.',
      accent: C.blue,
    },
    {
      who: 'The open project',
      what: 'Public to everyone, except the deploy keys that never leave the server.',
      accent: C.green,
    },
    {
      who: 'The monorepo',
      what: 'One repository, one clone URL, and each team receives only their half.',
      accent: C.purple,
    },
  ];
  return (
    <AbsoluteFill style={{justifyContent: 'center', alignItems: 'center', gap: 34}}>
      <div style={{fontFamily: SANS, fontSize: 52, fontWeight: 700, color: C.text, opacity: fade(frame, 2)}}>
        What it's for
      </div>
      <div style={{display: 'flex', gap: 28}}>
        {cases.map((c, i) => (
          <div
            key={c.who}
            style={{
              width: 480, minHeight: 250, padding: '26px 28px',
              background: C.panel, border: `1px solid ${C.border}`,
              borderTop: `3px solid ${c.accent}`, borderRadius: 12,
              opacity: fade(frame, 24 + i * 22, 16),
              transform: `translateY(${interpolate(fade(frame, 24 + i * 22, 16), [0, 1], [26, 0])}px)`,
            }}
          >
            <div style={{fontFamily: SANS, fontSize: 30, fontWeight: 700, color: c.accent, marginBottom: 12}}>
              {c.who}
            </div>
            <div style={{fontFamily: SANS, fontSize: 25, color: C.text, lineHeight: 1.5}}>{c.what}</div>
          </div>
        ))}
      </div>
    </AbsoluteFill>
  );
};

/* ------------------------------------------------------------------ what it took */

const HardPart: React.FC<{n: string; title: string; body: string; start: number}> = ({n, title, body, start}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const enter = spring({frame: frame - start, fps, config: {damping: 200}});
  return (
    <div
      style={{
        display: 'flex', gap: 20, alignItems: 'flex-start',
        opacity: enter, transform: `translateX(${interpolate(enter, [0, 1], [-22, 0])}px)`,
      }}
    >
      <div
        style={{
          flex: 'none', width: 42, height: 42, borderRadius: 21,
          border: `2px solid ${C.green}`, color: C.green,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontFamily: MONO, fontSize: 20,
        }}
      >
        {n}
      </div>
      <div style={{maxWidth: 1180}}>
        <div style={{fontFamily: SANS, fontSize: 31, fontWeight: 700, color: C.text}}>{title}</div>
        <div style={{fontFamily: SANS, fontSize: 24, color: C.muted, lineHeight: 1.45, marginTop: 4}}>{body}</div>
      </div>
    </div>
  );
};

// 1:03 — the part that isn't a UI.
const WhatItTook: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{justifyContent: 'center', paddingLeft: 180, paddingRight: 120, gap: 26}}>
      <div style={{fontFamily: SANS, fontSize: 52, fontWeight: 700, color: C.text, opacity: fade(frame, 2)}}>
        What it took
      </div>
      <Caption style={{fontSize: 25, textAlign: 'left', maxWidth: 1200, opacity: fade(frame, 10)}}>
        Git has no way to hand someone part of a repository. Everything below had to be built for
        that to become possible.
      </Caption>
      <div style={{display: 'flex', flexDirection: 'column', gap: 20, marginTop: 8}}>
        <HardPart
          n="1" start={30}
          title="A filtered view is a different commit graph"
          body="Remove a path and every tree, every commit and every descendant hash changes. The server rewrites history per user."
        />
        <HardPart
          n="2" start={68}
          title="The rewrite has to be deterministic"
          body="Same view, same object IDs — otherwise every fetch looks like a brand-new history instead of an incremental update."
        />
        <HardPart
          n="3" start={106}
          title="Pushes are merged back, not trusted"
          body="Readable paths come from the user; every hidden path is carried over from canonical. A push structurally cannot touch what the pusher can't see."
        />
        <HardPart
          n="4" start={144}
          title="Exclusions had to survive the cache"
          body="Views are shared by everyone with identical permissions, so a hidden file has to change the cache key — or someone gets served another user's view."
        />
        <HardPart
          n="5" start={182}
          title="Git only sends credentials after a 401"
          body="A public repo never issues one, so a member would silently receive the anonymous view. A dedicated clone path always challenges."
        />
      </div>
    </AbsoluteFill>
  );
};

// 1:22 — the receipts.
const Numbers: React.FC = () => {
  const frame = useCurrentFrame();
  const stats = [
    {n: '43', label: 'automated tests', hint: 'incl. real git clients over HTTP'},
    {n: '5.2k', label: 'lines of Java + TypeScript', hint: 'Spring Boot · JGit · React'},
    {n: '320px', label: 'smallest checked width', hint: 'every view, every change'},
  ];
  return (
    <AbsoluteFill style={{justifyContent: 'center', alignItems: 'center', gap: 40}}>
      <div style={{fontFamily: SANS, fontSize: 44, fontWeight: 700, color: C.text, opacity: fade(frame, 2)}}>
        Verified, not asserted
      </div>
      <div style={{display: 'flex', gap: 40}}>
        {stats.map((s, i) => (
          <div
            key={s.label}
            style={{
              width: 430, padding: '30px 28px', textAlign: 'center',
              background: C.panel, border: `1px solid ${C.border}`, borderRadius: 12,
              opacity: fade(frame, 20 + i * 18, 14),
              transform: `scale(${interpolate(fade(frame, 20 + i * 18, 14), [0, 1], [0.94, 1])})`,
            }}
          >
            <div style={{fontFamily: SANS, fontSize: 64, fontWeight: 800, color: C.green}}>{s.n}</div>
            <div style={{fontFamily: SANS, fontSize: 25, color: C.text, marginTop: 6}}>{s.label}</div>
            <div style={{fontFamily: SANS, fontSize: 19, color: C.muted, marginTop: 6}}>{s.hint}</div>
          </div>
        ))}
      </div>
      <Caption style={{fontSize: 24, opacity: fade(frame, 96)}}>
        The access-control guarantees are covered by tests, not by claims — and they run on every push.
      </Caption>
    </AbsoluteFill>
  );
};

// 1:28 — where to find it.
const TitleCard: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const pop = spring({frame: frame - 6, fps, config: {damping: 16}});
  return (
    <AbsoluteFill style={{justifyContent: 'center', alignItems: 'center', gap: 20}}>
      <div style={{display: 'flex', alignItems: 'center', gap: 22, transform: `scale(${pop})`}}>
        <Shield />
        <div style={{fontFamily: SANS, fontSize: 108, fontWeight: 800, color: C.text}}>GitGate</div>
      </div>
      <div style={{fontFamily: SANS, fontSize: 40, color: C.muted, opacity: fade(frame, 26)}}>
        File-level access control for Git.
      </div>
      <div style={{fontFamily: MONO, fontSize: 27, color: C.blue, opacity: fade(frame, 42), marginTop: 16}}>
        github.com/Boughzala-ayoub/gitgate
      </div>
      <div style={{fontFamily: SANS, fontSize: 23, color: C.muted, opacity: fade(frame, 56), marginTop: 12}}>
        Self-hostable · works with stock git clients
      </div>
    </AbsoluteFill>
  );
};

/* ------------------------------------------------------------------ the cut */

const BEATS: Array<{from: number; len: number; render: (len: number) => React.ReactNode}> = [
  {from: 0, len: 210, render: () => <Hook />},
  {from: 210, len: 180, render: (l) => <Repos frames={l} />},
  {from: 390, len: 210, render: (l) => <SettingsOverview frames={l} />},
  {from: 600, len: 270, render: (l) => <TreePicker frames={l} />},
  {from: 870, len: 300, render: (l) => <Exclusion frames={l} />},
  {from: 1170, len: 240, render: (l) => <WholeRepoExcept frames={l} />},
  {from: 1410, len: 180, render: (l) => <Phone frames={l} />},
  {from: 1590, len: 300, render: () => <UseCases />},
  {from: 1890, len: 420, render: () => <WhatItTook />},
  {from: 2310, len: 180, render: () => <Numbers />},
  {from: 2490, len: 210, render: () => <TitleCard />},
];

export const GitGateMarketing: React.FC = () => (
  <AbsoluteFill style={{background: C.bg}}>
    <AbsoluteFill
      style={{background: `radial-gradient(ellipse at 50% 45%, #161d27 0%, ${C.bg} 65%)`}}
    />
    {BEATS.map((b) => (
      <Sequence key={b.from} from={b.from} durationInFrames={b.len}>
        {b.render(b.len)}
      </Sequence>
    ))}
  </AbsoluteFill>
);
