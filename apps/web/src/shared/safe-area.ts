// Capacitor 8 supplies corrected values on Android; WKWebView and Safari use env().
// Keep application aliases separate so a native 0px value also overrides env().
export const safeAreaCss = `
  :root {
    --routy-safe-top: var(--safe-area-inset-top, env(safe-area-inset-top, 0px));
    --routy-safe-right: var(--safe-area-inset-right, env(safe-area-inset-right, 0px));
    --routy-safe-bottom: var(--safe-area-inset-bottom, env(safe-area-inset-bottom, 0px));
    --routy-safe-left: var(--safe-area-inset-left, env(safe-area-inset-left, 0px));
    --routy-safe-height: max(0px, calc(100dvh - var(--routy-safe-top) - var(--routy-safe-bottom)));
  }
  .safe-area-screen {
    min-height: 100dvh;
    padding: var(--routy-safe-top) var(--routy-safe-right) var(--routy-safe-bottom) var(--routy-safe-left);
  }
  .safe-area-screen::before, .safe-area-screen::after {
    content: ""; position: fixed; left: 0; right: 0;
    background: var(--background); pointer-events: none;
  }
  .safe-area-screen::before { top: 0; height: var(--routy-safe-top); z-index: 30; }
  .safe-area-screen::after { bottom: 0; height: var(--routy-safe-bottom); z-index: 10; }
`;
