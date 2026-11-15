"use client";
import { useEffect, useRef, useState, type FormEvent } from "react";
import Link from "next/link";
import type { AccountDeletionReceipt, AccountProfile } from "@routy/contracts";
import { request } from "@/lib/api/account";
import { ApiError } from "@/lib/api/accountClient";
import { deletionItems, privacy } from "@/shared/privacy";
import { AccountPageShell } from "./AccountPageShell";

export default function DeleteAccount() {
  const [user, setUser] = useState<AccountProfile | null>(null);
  const [busy, setBusy] = useState(true), [deleted, setDeleted] = useState(false), [error, setError] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const errorRef = useRef<HTMLParagraphElement>(null);
  useEffect(() => { let active = true;
    request<{ user: AccountProfile | null }>("auth/session").then(result => { if (active) setUser(result.user); })
      .catch(() => { if (active) setError("계정 상태를 확인하지 못했어요. 연결을 확인한 뒤 새로고침해주세요."); }).finally(() => { if (active) setBusy(false); });
    return () => { active = false; };
  }, []);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy) return;
    const form = event.currentTarget, data = new FormData(form), signedIn = !!user;
    setBusy(true); setError("");
    try {
      if (signedIn) {
        const result = await request<AccountDeletionReceipt>("account/delete", { password: data.get("password"), confirm: confirmed });
        if (result.deleted !== true) throw new Error("탈퇴 결과를 확인하지 못했어요. 다시 시도해주세요.");
        setDeleted(true); setUser(null); setConfirmed(false); form.reset();
      } else {
        const result = await request<{ user: AccountProfile }>("auth/login", { email: data.get("email"), password: data.get("password") });
        setUser(result.user); form.reset();
      }
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 401 && signedIn) { setUser(null); setConfirmed(false); }
      setError(cause instanceof Error ? cause.message : "잠시 후 다시 시도해주세요.");
      setTimeout(() => errorRef.current?.focus(), 0);
    } finally { setBusy(false); }
  }
  return <AccountPageShell><p className="eyebrow">계정과 개인정보</p><h1>{deleted ? "회원 탈퇴가 완료됐어요" : "회원 탈퇴"}</h1>
    {deleted ? <><p role="status">서버의 계정과 연결된 주행 기록을 삭제하고, 모든 기기의 로그인을 해제했어요.</p><div className="notice"><p>차량 앱에 보관된 원본 기록은 차량에서 앱 데이터를 초기화해 별도로 삭제해주세요. 다시 이용하려면 차량 앱 초기화 후 새 계정으로 연결해야 해요.</p><p className="fine">기존 백업 사본은 최대 {privacy.backupDays}일 이내 만료됩니다.</p></div><Link className="button" href="/">루티 홈으로</Link></> : <>
      <p>루티 계정과 연결된 기록을 삭제할 수 있어요. 앱을 설치하지 않은 브라우저에서도 이 페이지를 이용할 수 있어요.</p>
      <section className="panel danger" aria-labelledby="delete-scope"><h2 id="delete-scope">탈퇴하면 삭제되는 정보</h2><ul>{deletionItems.map(item => <li key={item}>{item}</li>)}</ul><p><strong>삭제한 계정과 기록은 복구할 수 없어요.</strong></p><p>기존 차량 설치 인증도 해제되어, 차량의 전송 대기 기록은 다시 업로드할 수 없어요.</p></section>
      <p className="fine">차량 기기에 남은 원본은 별도 초기화가 필요합니다. 백업 사본은 최대 {privacy.backupDays}일 이내 만료되며, 재전송 방지를 위한 설치 ID 해시와 백업 복구 시 삭제를 재적용하기 위한 최소 삭제 원장은 분리 관리합니다. <Link className="policy-link" href="/privacy#deletion">삭제 및 보관 정책 보기</Link></p>
      {error && <p className="error" role="alert" tabIndex={-1} ref={errorRef}>{error}</p>}
      {busy && !user ? <p role="status">계정 상태를 확인하고 있어요…</p> : <section className="panel"><h2>{user ? "본인 확인 후 탈퇴하기" : "먼저 본인 계정으로 로그인해주세요"}</h2>
        {user && <p>{user.displayName}님 · {user.email}</p>}
        <form onSubmit={submit}>{!user && <label>이메일<input name="email" type="email" autoComplete="email" maxLength={254} required disabled={busy}/></label>}
          <label>현재 비밀번호<input name="password" type="password" autoComplete="current-password" minLength={10} maxLength={128} required disabled={busy}/></label>
          {user && <label className="check"><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} required disabled={busy}/><span>삭제되는 정보와 복구할 수 없음을 확인했으며 회원 탈퇴에 동의합니다.</span></label>}
          <div className="actions"><Link className="button secondary" href="/">취소하고 돌아가기</Link><button className={user ? "delete-button" : ""} type="submit" disabled={busy || (!!user && !confirmed)}>{busy ? "처리하고 있어요…" : user ? "계정과 기록 영구 삭제" : "로그인하고 계속"}</button></div>
        </form></section>}
      <p className="fine">로그인할 수 없거나 개인정보 관련 도움이 필요하면 <a className="policy-link" href={`mailto:${privacy.email}`}>{privacy.email}</a>로 문의해주세요. 비밀번호를 이메일로 보내지 마세요.</p>
    </>}
  </AccountPageShell>;
}
