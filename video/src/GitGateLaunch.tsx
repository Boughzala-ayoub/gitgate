import React from 'react';
import {AbsoluteFill, Sequence, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {C, MONO, SANS, Typed, fade, fadeInOut} from './theme';

/* ------------------------------------------------------------------ shared bits */

const Window: React.FC<{
  title: string;
  accent: string;
  children: React.ReactNode;
  style?: React.CSSProperties;
}> = ({title, accent, children, style}) => (
  <div
    style={{
      background: C.panel,
      border: `1px solid ${C.border}`,
      borderRadius: 12,
      overflow: 'hidden',
      boxShadow: '0 24px 60px rgba(0,0,0,0.5)',
      ...style,
    }}
  >
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        padding: '14px 18px',
        borderBottom: `1px solid ${C.border}`,
        background: '#1b2129',
      }}
    >
      <Dot color="#ff5f57" />
      <Dot color="#febc2e" />
      <Dot color="#28c840" />
      <span style={{fontFamily: MONO, fontSize: 20, color: accent, marginLeft: 10}}>{title}</span>
    </div>
    <div style={{padding: '24px 26px', fontFamily: MONO, fontSize: 26, lineHeight: 1.7}}>
      {children}
    </div>
  </div>
);

const Dot: React.FC<{color: string}> = ({color}) => (
  <span style={{width: 12, height: 12, borderRadius: 6, background: color, display: 'inline-block'}} />
);

/** A file that arrived in a clone — slides in as it "lands". */
const FileRow: React.FC<{name: string; start: number; color?: string}> = ({
  name,
  start,
  color = C.text,
}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const enter = spring({frame: frame - start, fps, config: {damping: 200}});
  return (
    <div
      style={{
        opacity: enter,
        transform: `translateX(${interpolate(enter, [0, 1], [-24, 0])}px)`,
        color,
        whiteSpace: 'nowrap',
      }}
    >
      <span style={{color: C.muted}}>│ </span>
      {name}
    </div>
  );
};

const Caption: React.FC<{children: React.ReactNode; style?: React.CSSProperties}> = ({
  children,
  style,
}) => (
  <div
    style={{
      fontFamily: SANS,
      fontSize: 34,
      color: C.muted,
      textAlign: 'center',
      ...style,
    }}
  >
    {children}
  </div>
);

/* ------------------------------------------------------------------ scenes */

// 0:00 — the problem, stated flat.
const Hook: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{justifyContent: 'center', alignItems: 'center', gap: 34}}>
      <div
        style={{
          fontFamily: SANS,
          fontSize: 96,
          fontWeight: 700,
          color: C.text,
          opacity: fadeInOut(frame, 8, 118),
          transform: `translateY(${interpolate(fade(frame, 8, 20), [0, 1], [26, 0])}px)`,
        }}
      >
        Git can't hide files.
      </div>
      <Caption style={{fontSize: 40, opacity: fadeInOut(frame, 46, 74)}}>
        Clone a repository and you receive <em>every</em> file in its history.
      </Caption>
    </AbsoluteFill>
  );
};

// 0:06 — one URL, one command.
const OneUrl: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{justifyContent: 'center', alignItems: 'center', gap: 46}}>
      <Caption style={{opacity: fade(frame, 4)}}>One repository. One clone URL.</Caption>
      <Window
        title="~/work"
        accent={C.muted}
        style={{width: 1150, opacity: fade(frame, 14), transform: `scale(${interpolate(fade(frame, 14, 18), [0, 1], [0.95, 1])})`}}
      >
        <div>
          <span style={{color: C.green}}>$ </span>
          <Typed text="git clone https://git.acme.com/app.git" start={28} />
        </div>
      </Window>
      <Caption style={{opacity: fadeInOut(frame, 150, 40), fontSize: 30}}>
        Now run it as three different people.
      </Caption>
    </AbsoluteFill>
  );
};

// 0:14 — the reveal: same command, three different results.
const ThreeClones: React.FC = () => {
  const frame = useCurrentFrame();
  const users = [
    {
      name: 'alice',
      accent: C.blue,
      files: [
        {name: 'frontend/app.js', color: C.text},
        {name: 'README.md', color: C.text},
      ],
    },
    {
      name: 'bob',
      accent: C.purple,
      files: [
        {name: 'backend/db.js', color: C.text},
        {name: 'README.md', color: C.text},
      ],
    },
    {
      name: 'admin',
      accent: C.yellow,
      files: [
        {name: 'frontend/app.js', color: C.text},
        {name: 'backend/db.js', color: C.text},
        {name: 'README.md', color: C.text},
        {name: 'TOPSECRET.txt', color: C.red},
      ],
    },
  ];

  return (
    <AbsoluteFill style={{justifyContent: 'center', alignItems: 'center', gap: 40}}>
      <Caption style={{opacity: fade(frame, 2)}}>
        <span style={{fontFamily: MONO, color: C.text}}>git clone https://git.acme.com/app.git</span>
      </Caption>
      <div style={{display: 'flex', gap: 34}}>
        {users.map((u, i) => (
          <Window
            key={u.name}
            title={u.name}
            accent={u.accent}
            style={{
              width: 560,
              minHeight: 360,
              opacity: fade(frame, 12 + i * 8, 14),
              transform: `translateY(${interpolate(fade(frame, 12 + i * 8, 14), [0, 1], [30, 0])}px)`,
            }}
          >
            <div style={{color: C.muted, fontSize: 21, marginBottom: 12}}>
              <Typed
                text={`Receiving objects: 100%`}
                start={50 + i * 8}
                cps={40}
                color={C.muted}
                cursor={false}
              />
            </div>
            {u.files.map((f, j) => (
              <FileRow key={f.name} name={f.name} color={f.color} start={90 + i * 10 + j * 12} />
            ))}
          </Window>
        ))}
      </div>
      <Caption style={{opacity: fade(frame, 210), fontSize: 30}}>
        Same command. Same URL. Enforced by the server.
      </Caption>
    </AbsoluteFill>
  );
};

// 0:24 — say the thing plainly.
const Statement: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{justifyContent: 'center', alignItems: 'center', gap: 24}}>
      <div
        style={{
          fontFamily: SANS,
          fontSize: 86,
          fontWeight: 600,
          color: C.muted,
          opacity: fadeInOut(frame, 6, 160),
        }}
      >
        Same URL. Same repository.
      </div>
      <div
        style={{
          fontFamily: SANS,
          fontSize: 110,
          fontWeight: 800,
          color: C.green,
          opacity: fadeInOut(frame, 48, 118),
          transform: `scale(${interpolate(fade(frame, 48, 16), [0, 1], [0.9, 1])})`,
        }}
      >
        Different bytes.
      </div>
    </AbsoluteFill>
  );
};

// 0:32 — the proof that closes the "it's just a checkout trick" objection.
const Proof: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{justifyContent: 'center', alignItems: 'center', gap: 42}}>
      <Caption style={{opacity: fade(frame, 2)}}>
        Inside alice's clone — asking for the hidden file by its exact hash:
      </Caption>
      <Window title="alice" accent={C.blue} style={{width: 1400, opacity: fade(frame, 10)}}>
        <div>
          <span style={{color: C.green}}>$ </span>
          <Typed text="git cat-file -t 49012077d5bc945bf84221575c781f640fa39026" start={26} cps={30} />
        </div>
        <div style={{color: C.red, opacity: fade(frame, 116), marginTop: 10}}>
          fatal: could not get object info
        </div>
      </Window>
      <div style={{opacity: fade(frame, 150), textAlign: 'center'}}>
        <Caption style={{fontSize: 38, color: C.text}}>
          Not hidden from the checkout.
        </Caption>
        <Caption style={{fontSize: 38, color: C.green, marginTop: 6}}>
          Never sent over the wire.
        </Caption>
      </div>
    </AbsoluteFill>
  );
};

const CommitNode: React.FC<{label: string; sub: string; start: number; color: string}> = ({
  label,
  sub,
  start,
  color,
}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const pop = spring({frame: frame - start, fps, config: {damping: 14, mass: 0.6}});
  return (
    <div style={{textAlign: 'center', opacity: Math.min(1, pop * 1.4), transform: `scale(${pop})`}}>
      <div
        style={{
          width: 96,
          height: 96,
          borderRadius: 48,
          border: `3px solid ${color}`,
          background: C.panel,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontFamily: MONO,
          fontSize: 30,
          color,
        }}
      >
        {label}
      </div>
      <div style={{fontFamily: MONO, fontSize: 18, color: C.muted, marginTop: 10}}>{sub}</div>
    </div>
  );
};

const Connector: React.FC<{start: number; color: string}> = ({start, color}) => {
  const frame = useCurrentFrame();
  return (
    <div
      style={{
        width: 90,
        height: 3,
        background: color,
        opacity: fade(frame, start, 8),
        transformOrigin: 'left center',
        transform: `scaleX(${fade(frame, start, 10)})`,
        marginBottom: 34,
      }}
    />
  );
};

// 0:44 — how it works, in one diagram.
const HowItWorks: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{justifyContent: 'center', alignItems: 'center', gap: 30}}>
      <Caption style={{opacity: fade(frame, 2), fontSize: 32}}>
        A filtered view is a <em>different commit graph</em> — so GitGate rewrites history per user.
      </Caption>

      <div style={{display: 'flex', alignItems: 'center', gap: 0, opacity: fade(frame, 10)}}>
        <div style={{width: 420, textAlign: 'right', paddingRight: 28}}>
          <div style={{fontFamily: SANS, fontSize: 28, color: C.text}}>canonical</div>
          <div style={{fontFamily: MONO, fontSize: 18, color: C.muted, whiteSpace: 'nowrap'}}>
            frontend · backend · README · TOPSECRET
          </div>
        </div>
        <CommitNode label="C1" sub="" start={16} color={C.muted} />
        <Connector start={26} color={C.border} />
        <CommitNode label="C2" sub="" start={32} color={C.muted} />
        <Connector start={42} color={C.border} />
        <CommitNode label="C3" sub="" start={48} color={C.muted} />
      </div>

      <div
        style={{
          fontFamily: MONO,
          fontSize: 24,
          color: C.green,
          opacity: fade(frame, 70),
          margin: '4px 0',
        }}
      >
        ↓ rewrite for alice's permissions
      </div>

      <div style={{display: 'flex', alignItems: 'center', opacity: fade(frame, 78)}}>
        <div style={{width: 420, textAlign: 'right', paddingRight: 28}}>
          <div style={{fontFamily: SANS, fontSize: 28, color: C.blue}}>alice's view</div>
          <div style={{fontFamily: MONO, fontSize: 18, color: C.muted, whiteSpace: 'nowrap'}}>
            frontend · README
          </div>
        </div>
        <CommitNode label="C1'" sub="" start={84} color={C.blue} />
        <Connector start={94} color={C.blue} />
        <CommitNode label="C2'" sub="" start={100} color={C.blue} />
        <Connector start={110} color={C.blue} />
        <CommitNode label="C3'" sub="" start={116} color={C.blue} />
      </div>

      <Caption style={{opacity: fade(frame, 150), fontSize: 28, maxWidth: 1350, marginTop: 10}}>
        Deterministic, so the same view always yields the same object IDs and{' '}
        <span style={{color: C.text}}>fetch stays incremental</span>. Pushes are merged back onto
        canonical history — a push structurally cannot touch what the pusher can't see.
      </Caption>
    </AbsoluteFill>
  );
};

// 0:54 — who made it and where to find it.
const TitleCard: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const pop = spring({frame: frame - 6, fps, config: {damping: 16}});
  return (
    <AbsoluteFill style={{justifyContent: 'center', alignItems: 'center', gap: 22}}>
      <div style={{display: 'flex', alignItems: 'center', gap: 22, transform: `scale(${pop})`}}>
        <Shield />
        <div style={{fontFamily: SANS, fontSize: 112, fontWeight: 800, color: C.text}}>GitGate</div>
      </div>
      <div style={{fontFamily: SANS, fontSize: 42, color: C.muted, opacity: fade(frame, 28)}}>
        File-level access control for Git.
      </div>
      <div
        style={{
          fontFamily: MONO,
          fontSize: 28,
          color: C.blue,
          opacity: fade(frame, 44),
          marginTop: 18,
        }}
      >
        github.com/Boughzala-ayoub/gitgate
      </div>
      <div style={{fontFamily: SANS, fontSize: 26, color: C.muted, opacity: fade(frame, 56), marginTop: 16}}>
        Spring Boot · JGit · works with stock git clients
      </div>
    </AbsoluteFill>
  );
};

const Shield: React.FC = () => (
  <svg width="92" height="92" viewBox="0 0 24 24" fill="none">
    <path
      d="M12 2 4 5v6c0 5 3.4 9.4 8 11 4.6-1.6 8-6 8-11V5l-8-3Z"
      fill={C.green}
      opacity={0.15}
      stroke={C.green}
      strokeWidth={1.6}
    />
    <path d="M8.5 12.2l2.4 2.4 4.6-4.8" stroke={C.green} strokeWidth={2} strokeLinecap="round" />
  </svg>
);

/* ------------------------------------------------------------------ the cut */

export const GitGateLaunch: React.FC = () => (
  <AbsoluteFill style={{background: C.bg}}>
    <Vignette />
    <Sequence durationInFrames={180}>
      <Hook />
    </Sequence>
    <Sequence from={180} durationInFrames={240}>
      <OneUrl />
    </Sequence>
    <Sequence from={420} durationInFrames={300}>
      <ThreeClones />
    </Sequence>
    <Sequence from={720} durationInFrames={240}>
      <Statement />
    </Sequence>
    <Sequence from={960} durationInFrames={360}>
      <Proof />
    </Sequence>
    <Sequence from={1320} durationInFrames={300}>
      <HowItWorks />
    </Sequence>
    <Sequence from={1620} durationInFrames={180}>
      <TitleCard />
    </Sequence>
  </AbsoluteFill>
);

const Vignette: React.FC = () => (
  <AbsoluteFill
    style={{
      background: `radial-gradient(ellipse at 50% 45%, #161d27 0%, ${C.bg} 65%)`,
    }}
  />
);
