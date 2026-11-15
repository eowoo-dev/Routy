import { test } from "node:test";
import assert from "node:assert/strict";
import { authHref, connectionCode, connectionHref, recordHref, safeDestination } from "../src/features/routy/routes";

const session="eb6f3684-3007-4222-a105-756da01db380";
test("record detail survives the URL round trip, including the login return address",()=>{
  const detail=recordHref(session);
  assert.equal(detail,`/records?session=${session}`);
  const login=new URL(authHref("login",detail),"https://routy.eowoo.org");
  assert.equal(login.pathname,"/login");
  assert.equal(safeDestination(login.searchParams.get("next")),detail);
  assert.equal(safeDestination("/records?session=not-a-uuid"),"/records");
});
test("legacy QR and typed pairing codes survive sign-in and refresh without losing the code",()=>{
  const target=safeDestination("/?connect=abcd-efgh");
  assert.equal(target,"/connect?code=ABCDEFGH");
  for(const mode of ["login","signup"] as const){
    const url: URL=new URL(authHref(mode,target),"https://routy.eowoo.org");
    assert.equal(safeDestination(url.searchParams.get("next")),target);
  }
  assert.equal(connectionHref(connectionCode("abcd-efgh")),target);
});
test("return addresses cannot leave the app, execute scripts or redirect into another auth page",()=>{
  for(const value of [null,"https://evil.example/records","//evil.example","/\\evil.example","javascript:alert(1)","/%2f%2fevil.example","/privacy","/login?next=/login","/records/../../admin"]){
    assert.equal(safeDestination(value),"/",String(value));
  }
  assert.equal(safeDestination("/connect?code=abcd-efgh&next=https://evil.example"),"/connect?code=ABCDEFGH");
  assert.equal(safeDestination("/records?session="+session+"&accountId=another-user"),recordHref(session));
});
