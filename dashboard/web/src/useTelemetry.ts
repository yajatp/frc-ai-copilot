import { useEffect, useRef, useState } from "react";
import type { Hello, Point, Tick } from "./types";

/** Points retained per signal in the browser — about a minute at the 10 Hz tick rate. */
const MAX_POINTS = 600;

export interface Telemetry {
  /** The most recent frame, or null before the first one arrives. */
  tick: Tick | null;
  /** Accumulated chart history per signal role, seeded from the opening frame. */
  series: Record<string, Point[]>;
  /** Whether the browser currently holds an open stream to the dashboard process. */
  streamOpen: boolean;
}

/**
 * Subscribes to the dashboard's event stream.
 *
 * <p>Chart history lives here rather than in the frames: the server sends a decimated window once
 * on connect and then only latest values, so a tab that has been open for ten minutes is not
 * re-sent ten minutes of samples ten times a second.
 *
 * EventSource reconnects on its own if the process restarts or the link drops, which is the
 * behaviour we want in a pit.
 */
export function useTelemetry(): Telemetry {
  const [tick, setTick] = useState<Tick | null>(null);
  const [streamOpen, setStreamOpen] = useState(false);
  const [series, setSeries] = useState<Record<string, Point[]>>({});

  // Frames arrive at 10 Hz; buffer them in a ref and flush on an animation frame so React
  // re-renders at the display's pace rather than the network's.
  const pending = useRef<Tick | null>(null);
  const frameHandle = useRef<number | null>(null);

  useEffect(() => {
    const source = new EventSource("/api/stream");

    const flush = () => {
      frameHandle.current = null;
      const frame = pending.current;
      if (!frame) return;
      pending.current = null;
      setTick(frame);
      setSeries((prev) => appendFrame(prev, frame));
    };

    const schedule = (frame: Tick) => {
      pending.current = frame;
      if (frameHandle.current === null) {
        frameHandle.current = requestAnimationFrame(flush);
      }
    };

    source.addEventListener("hello", (event) => {
      const frame = JSON.parse((event as MessageEvent).data) as Hello;
      setStreamOpen(true);
      setSeries(seedFromHistory(frame));
      setTick(frame);
    });

    source.addEventListener("tick", (event) => {
      schedule(JSON.parse((event as MessageEvent).data) as Tick);
    });

    source.onopen = () => setStreamOpen(true);
    source.onerror = () => setStreamOpen(false);

    return () => {
      source.close();
      if (frameHandle.current !== null) cancelAnimationFrame(frameHandle.current);
    };
  }, []);

  return { tick, series, streamOpen };
}

function seedFromHistory(frame: Hello): Record<string, Point[]> {
  const seeded: Record<string, Point[]> = {};
  for (const [role, points] of Object.entries(frame.history ?? {})) {
    seeded[role] = points.map(([t, v]) => ({ t, v }));
  }
  return seeded;
}

function appendFrame(prev: Record<string, Point[]>, frame: Tick): Record<string, Point[]> {
  const next: Record<string, Point[]> = { ...prev };
  for (const [role, reading] of Object.entries(frame.signals)) {
    if (reading.value === null) continue;
    const existing = next[role] ?? [];
    // The robot re-publishes on its own clock; skip duplicates so charts do not flatline
    // horizontally when a signal stops updating.
    if (existing.length > 0 && existing[existing.length - 1].t === reading.tMs) continue;
    const appended = [...existing, { t: reading.tMs, v: reading.value }];
    next[role] = appended.length > MAX_POINTS ? appended.slice(-MAX_POINTS) : appended;
  }
  return next;
}
