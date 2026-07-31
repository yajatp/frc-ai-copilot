import type { Confidence, Severity } from "./types";

export interface SeverityStyle {
  dot: string;
  text: string;
  bg: string;
  border: string;
  label: string;
}

/**
 * Severity styling. Healthy state is deliberately quiet — muted, uncoloured — so that a glance
 * across the pit view registers colour only where something actually needs attention.
 */
export function severityStyle(severity: Severity): SeverityStyle {
  switch (severity) {
    case "CRITICAL":
      return {
        dot: "var(--theme-red)",
        text: "var(--theme-red)",
        bg: "var(--theme-redBg)",
        border: "var(--theme-red)",
        label: "Critical",
      };
    case "WATCH":
      return {
        dot: "var(--theme-amberDot)",
        text: "var(--theme-amberText)",
        bg: "var(--theme-amberBg)",
        border: "var(--theme-amberBorder)",
        label: "Watch",
      };
    default:
      return {
        dot: "var(--theme-trackFill)",
        text: "var(--theme-textMuted)",
        bg: "transparent",
        border: "var(--theme-border)",
        label: "OK",
      };
  }
}

/** Confidence is shown as-is: an unverifiable reading must not look like a confident one. */
export function confidenceLabel(confidence: Confidence): string {
  switch (confidence) {
    case "HIGH":
      return "High confidence";
    case "MEDIUM":
      return "Medium confidence";
    case "LOW":
      return "Low confidence";
    default:
      return "No data";
  }
}

/** Formats a reading for display, keeping "no reading" visually distinct from zero. */
export function formatValue(value: number | null, unit: string): string {
  if (value === null || !Number.isFinite(value)) return "—";
  const decimals = Math.abs(value) >= 100 ? 0 : Math.abs(value) >= 10 ? 1 : 2;
  return `${value.toFixed(decimals)}${unit ? ` ${unit}` : ""}`;
}
