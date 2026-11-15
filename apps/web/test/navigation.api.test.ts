import { test } from "node:test";
import assert from "node:assert/strict";
const base=process.env.RECORDS_TEST_API??"http://127.0.0.1:3000";
test("direct navigation and refresh URLs resolve to exported pages instead of returning 404",async()=>{
  for(const [path,title] of [["/records","주행 기록"],["/connect?code=ABCDEFGH","차량 연결"],["/login?next=%2Frecords","로그인"],["/signup?next=%2Fconnect","회원 가입"],["/records?session=eb6f3684-3007-4222-a105-756da01db380","주행 기록"]]){
    const response=await fetch(base+path);
    assert.equal(response.status,200,path);
    assert.ok((await response.text()).includes(`<title>${title} | 루티</title>`),path);
  }
});
