"use client";

import { css, Global } from "@emotion/react";

export default function GlobalStyles() {
  return <Global styles={css`
    *, *::before, *::after { box-sizing: border-box; }
    html { color-scheme: light; }
    body {
      margin: 0;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Apple SD Gothic Neo", sans-serif;
      font-size: 16px;
      line-height: 1.5;
      color: #202124;
      background: #fff;
      word-break: keep-all;
    }
    main { padding: max(24px, env(safe-area-inset-top)) 24px 24px; }
    button, input, textarea, select { font: inherit; }
    :focus-visible { outline: 2px solid currentColor; outline-offset: 3px; }
  `} />;
}
