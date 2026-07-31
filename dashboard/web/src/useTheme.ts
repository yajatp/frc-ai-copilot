import { useCallback, useEffect, useState } from "react";

export type Theme = "light" | "dark";

/** Shared with the inline bootstrap script in index.html — keep the two in sync. */
export const THEME_STORAGE_KEY = "frc-dashboard-theme";

function currentTheme(): Theme {
  return document.documentElement.dataset.theme === "dark" ? "dark" : "light";
}

/**
 * Reads and toggles the active theme.
 *
 * <p>The initial value comes from whatever the bootstrap script in {@code index.html} already
 * applied, rather than being decided here — deciding it in React would mean one paint in the wrong
 * theme before hydration, which is exactly the flash the bootstrap exists to prevent.
 */
export function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(currentTheme);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem(THEME_STORAGE_KEY, theme);
    } catch {
      // Private browsing or a locked-down profile: the toggle still works for this session.
    }
  }, [theme]);

  const toggle = useCallback(() => {
    setTheme((prev) => (prev === "dark" ? "light" : "dark"));
  }, []);

  return [theme, toggle];
}
