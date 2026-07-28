import React from 'react';
import {interpolate, useCurrentFrame} from 'remotion';

/** GitHub dark palette — the dashboard is Primer-styled, so the video matches the product. */
export const C = {
  bg: '#0d1117',
  panel: '#161b22',
  border: '#30363d',
  text: '#e6edf3',
  muted: '#8b949e',
  green: '#3fb950',
  blue: '#58a6ff',
  red: '#f85149',
  yellow: '#d29922',
  purple: '#bc8cff',
};

export const MONO = "'Cascadia Mono', Consolas, 'SF Mono', Menlo, monospace";
export const SANS =
  "-apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif";

/** 0 → 1 over `dur` frames starting at `start`, clamped at both ends. */
export const fade = (frame: number, start: number, dur = 12) =>
  interpolate(frame, [start, start + dur], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

/** Fades in, holds, fades out — for text that must clear before the next beat. */
export const fadeInOut = (frame: number, start: number, hold: number, dur = 12) =>
  Math.min(fade(frame, start, dur), 1 - fade(frame, start + dur + hold, dur));

/** Reveals `text` a character at a time, with a blinking cursor while typing. */
export const Typed: React.FC<{
  text: string;
  start: number;
  cps?: number;
  color?: string;
  cursor?: boolean;
}> = ({text, start, cps = 26, color = C.text, cursor = true}) => {
  const frame = useCurrentFrame();
  const elapsed = Math.max(0, frame - start);
  const shown = Math.min(text.length, Math.floor((elapsed / 30) * cps));
  const done = shown >= text.length;
  const blink = Math.floor(frame / 15) % 2 === 0;

  if (frame < start) return null;
  return (
    <span style={{color}}>
      {text.slice(0, shown)}
      {cursor && (!done || blink) ? (
        <span style={{color: C.green, opacity: done && !blink ? 0 : 1}}>▋</span>
      ) : null}
    </span>
  );
};
