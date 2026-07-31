/** Shapes mirroring the JSON emitted by DashboardServer. */

/** Shared with Mode A, so live tiles and post-match reports speak one vocabulary. */
export type Severity = "OK" | "WATCH" | "CRITICAL";

export type Confidence = "HIGH" | "MEDIUM" | "LOW" | "NONE";

/** One health tile: a primitive's verdict over the current rolling window. */
export interface Verdict {
  role: string;
  label: string;
  severity: Severity;
  /** Null when the signal is missing or the reading is not finite. */
  value: number | null;
  unit: string;
  assessment: string;
  confidence: Confidence;
  /** The NT topic this resolved to, or null when the robot does not publish it. */
  signal: string | null;
}

export interface SignalReading {
  key: string;
  unit: string;
  value: number | null;
  tMs: number;
}

export interface FmsInfo {
  attached: boolean;
  eventName: string;
  matchNumber: number;
  matchType: number;
  station: number;
  isRedAlliance: boolean | null;
}

export interface Tick {
  t: number;
  connected: boolean;
  topics: number;
  signals: Record<string, SignalReading>;
  health: Verdict[];
  fms: FmsInfo;
}

/** The opening frame also carries chart history so a new tab is not blank. */
export interface Hello extends Tick {
  history: Record<string, [number, number][]>;
}

/** A chart point: milliseconds since the robot's epoch, and the value. */
export type Point = { t: number; v: number };
