"use client";
import { css, Global } from "@emotion/react";
import tokens from "@routy/contracts/design";
export default function GlobalStyles() {
  return <Global styles={css`
    @font-face {font-family: SUIT;src:url('/fonts/SUIT-Variable.woff2') format('woff2');font-style:normal;font-weight:100 900;font-display:swap;}
    :root {${Object.entries(tokens.colors).map(([key,value])=>`--${key}:${value};`).join("")}}
    *,*::before,*::after {box-sizing:border-box;}
    html {color-scheme:light;scroll-behavior:smooth;}
    body {margin:0;font-family:SUIT,"Apple SD Gothic Neo","Malgun Gothic",sans-serif;font-size:16px;line-height:1.65;color:var(--ink);background:var(--background);word-break:keep-all;overflow-wrap:anywhere;}
    button,input,textarea,select {font:inherit;}
    button,a { -webkit-tap-highlight-color:transparent; }
    button {cursor:pointer;}
    button:disabled {cursor:wait;opacity:.55;}
    a {color:inherit;text-decoration:none;}
    :focus-visible {outline:3px solid var(--primary);outline-offset:4px;}
    ::selection {background:var(--mint);}
    @media(prefers-reduced-motion:reduce){html{scroll-behavior:auto;}*,*::before,*::after{animation:none!important;transition:none!important;}}
  `}/>;
}
