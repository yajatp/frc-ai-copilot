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

// --- Phase 2: API response types ---

/** One waypoint in a PathPlanner .path file. */
export interface Waypoint {
  anchor: { x: number; y: number };
  prevControl: { x: number; y: number } | null;
  nextControl: { x: number; y: number } | null;
  isLocked: boolean;
  linkedName: string | null;
}

/** A parsed PathPlanner .path file from /api/paths. */
export interface PathData {
  name: string;
  version: string;
  waypoints: Waypoint[];
  globalConstraints: {
    maxVelocity: number;
    maxAcceleration: number;
    maxAngularVelocity?: number;
    maxAngularAcceleration?: number;
    [key: string]: number | undefined;
  };
  goalEndState?: { velocity: number; rotation: number };
  idealStartingState?: { velocity: number; rotation: number };
}

/** A single trend data point from /api/trends. */
export interface TrendPoint {
  logId: number;
  matchKey: string | null;
  phase: string;
  value: number;
  unit: string;
}

/** A log summary row from /api/logs. */
export interface LogRow {
  id: number;
  path: string;
  matchKey: string | null;
  durationS: number;
  gitSha: string | null;
}

/** A flagged event from /api/events. */
export interface EventRow {
  logId: number;
  tsUs: number;
  kind: string;
  severity: Severity;
  detail: string;
}

/** Robot profile from /api/profile. */
export interface ProfileData {
  loaded: boolean;
  team?: number;
  robot?: string;
  season?: number;
  game?: string;
  vendors?: string[];
  drivetrain?: {
    massKg?: number;
    moiKgM2?: number;
    trackwidthM?: number;
    wheelRadiusM?: number;
    gearing?: number;
    maxSpeedMps?: number;
    driveMotor?: string;
    driveCurrentLimitA?: number;
    wheelCof?: number;
    robotWidthM?: number;
    robotLengthM?: number;
    moduleOffsets?: { name: string; xM: number; yM: number }[];
  };
  devices?: {
    canId?: number;
    label: string;
    subsystem?: string;
    vendor?: string;
    source?: string;
    accurate: boolean;
  }[];
  mechanisms?: {
    name: string;
    type?: string;
    degreesOfFreedom?: number;
    maxHeightMeters?: number;
  }[];
  subsystems?: string[];
  field?: {
    game?: string;
    season?: number;
    aprilTagField?: string;
    lengthM?: number;
    widthM?: number;
  };
}

