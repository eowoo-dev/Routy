"use client";
import styled from "@emotion/styled";
import Link from "next/link";
import type { ReactNode } from "react";

export function AccountPageShell({ children }: { children: ReactNode }) {
  return <Shell><a className="skip" href="#account-main">본문으로 이동</a><header><Link className="wordmark" href="/" aria-label="루티 홈">routy</Link><Link href="/">내 연습으로 돌아가기</Link></header><main id="account-main">{children}</main><footer><span>어쩌다우리 · EoWoo</span><nav aria-label="개인정보 및 계정"><Link href="/privacy">개인정보처리방침</Link><Link href="/account/delete">회원 탈퇴</Link></nav></footer></Shell>;
}

const Shell = styled.div`
  min-height:var(--routy-safe-height);
  .skip{position:absolute;left:calc(16px + var(--routy-safe-left));top:-100px;}.skip:focus{top:calc(8px + var(--routy-safe-top));background:var(--surface);padding:12px;z-index:40;}
  header,footer{max-width:1040px;margin:auto;padding:24px 32px;display:flex;justify-content:space-between;align-items:center;gap:24px;flex-wrap:wrap;}
  header{border-bottom:1px solid var(--border);}header a,footer a{display:inline-flex;align-items:center;min-height:48px;color:var(--primary);font-weight:650;}
  .wordmark{font-size:32px;font-weight:800;letter-spacing:-1.5px;}main{max-width:820px;margin:auto;padding:48px 32px 72px;}
  h1{font-size:clamp(30px,5vw,44px);line-height:1.25;letter-spacing:-1.5px;margin:12px 0 20px;}h2{font-size:23px;line-height:1.45;margin:0 0 14px;}h3{font-size:18px;margin:24px 0 12px;}
  p{margin:0 0 16px;}section{margin:36px 0;}ul,ol{padding-left:24px;}li{margin:8px 0;}a:hover{text-decoration:underline;text-underline-offset:4px;}
  .eyebrow{font-weight:700;color:var(--primary);margin:0;}.muted{color:var(--muted);}.notice,.panel{padding:24px;border:1px solid var(--border);border-radius:20px;background:var(--surface);margin:24px 0;}
  .notice{background:var(--mint);}.danger{border-left:4px solid var(--error);}.error{color:var(--error);padding:14px 16px;border:1px solid currentColor;border-radius:12px;}
  label{display:block;font-weight:650;margin:20px 0 8px;}input[type=email],input[type=password]{display:block;width:100%;margin-top:8px;padding:14px 16px;border:1px solid var(--border);border-radius:12px;background:var(--background);color:var(--ink);}
  .check{display:flex;align-items:flex-start;gap:12px;padding:12px 0;min-height:48px;cursor:pointer;}.check input{width:22px;height:22px;flex:0 0 auto;margin:3px 0 0;accent-color:var(--primary);}
  .actions{display:flex;align-items:center;gap:16px;flex-wrap:wrap;margin-top:24px;}button,.button{min-height:52px;padding:14px 22px;border:0;border-radius:14px;display:inline-flex;align-items:center;justify-content:center;font-weight:700;background:var(--primary);color:var(--onPrimary);}
  .delete-button{background:var(--error);}.secondary{background:transparent;color:var(--primary);border:1px solid var(--border);}button:active:not(:disabled),.button:active{opacity:.8;}
  .fine{font-size:14px;line-height:1.7;color:var(--muted);}footer{border-top:1px solid var(--border);color:var(--muted);font-size:14px;}footer nav{display:flex;gap:24px;flex-wrap:wrap;}.policy-link{color:var(--primary);text-decoration:underline;text-underline-offset:4px;}
  @media(max-width:600px){header,footer{padding:16px 20px;gap:12px;}main{padding:32px 20px 48px;}.notice,.panel{padding:20px;}.actions>*{width:100%;}footer nav{gap:16px;}}
`;
