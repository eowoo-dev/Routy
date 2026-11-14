import { test } from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import type { DrivingRecordDetail } from "@routy/contracts";
import { RecordDetail } from "../src/features/routy/RoutyApp";
import { archiveReviewPreview } from "../src/server/driving-records/review-preview";

const group={id:"environment-1",startMs:100,endMs:1000,title:"차로 변경 중 신호가 꺼졌어요",nextPractice:"변경을 마칠 때까지 유지해보세요",kinds:["INTERRUPTED_LANE_CHANGE_SIGNAL"],evidence:{signalOffDurationMs:500},limited:false};
const feedback={...group,observation:"중간 해제 관측"};
const record=(report:Record<string,unknown>)=>({sessionId:"123",startedAt:"2026-11-14T00:00:00Z",durationMs:1000,origin:"LIVE",report,archive:{parts:1,bytes:100,sha256:"a".repeat(64)}} as unknown as DrivingRecordDetail);
const html=(report:Record<string,unknown>)=>renderToStaticMarkup(createElement(RecordDetail,{record:record(report)}));

test("environment feedback survives an absent review and a represented group is shown once",()=>{
  assert.match(html({startMs:0,feedback:[feedback],environment:{}}),/차로 변경 중 신호가 꺼졌어요/);
  const result=html({startMs:0,feedback:[feedback],environment:{review:{groups:[group]}}});
  assert.equal(result.split(group.title).length-1,1);
  assert.match(result,/변경을 마칠 때까지 유지해보세요/);
  assert.match(result,/전체 상세 평가 내려받기/);
});
test("interrupted and segmented records explain their limits",()=>{
  const result=html({segmentation:{windows:4},recovery:{status:"INTERRUPTED"},feedback:[]});
  assert.match(result,/정상 주차 종료를 확인한 기록과 구분/);
  assert.match(result,/복습 항목 일부/);assert.match(result,/모든 항목을 통과했다는 뜻은 아니에요/);
});
async function* chunks(text:string,size=7){const b=Buffer.from(text);for(let i=0;i<b.length;i+=size)yield b.subarray(i,i+size);}
test("legacy archive restores Korean feedback across UTF-8 part boundaries and merges overlapping context",async()=>{
  const line=(g:typeof group,coreStartMs:number)=>JSON.stringify({coreStartMs,report:{environment:{review:{groups:[g]}}}})+"\n";
  const result=await archiveReviewPreview(chunks(line(group,0)+line({...group,startMs:500,endMs:1500},1000)));
  assert.equal(result.review.groups.length,1);assert.equal(result.review.groups[0].endMs,1500);
  assert.equal(result.review.groups[0].title,group.title);assert.deepEqual(result.review.quality,[]);
  assert.match(html({environment:{review:result.review},reviewPreview:result.preview}),/상세에서 복습 내용을 복원/);
});
test("preview stays bounded and omits context events already finished before the core",async()=>{
  const lines=Array.from({length:100},(_,i)=>JSON.stringify({coreStartMs:0,report:{environment:{review:{groups:[{...group,startMs:i*2000,endMs:i*2000+1000}]}}}})).join("\n");
  const result=await archiveReviewPreview(chunks(lines,196608));
  assert.equal(result.review.groups.length,32);assert.equal(result.preview.limited,true);
  const old=await archiveReviewPreview(chunks(JSON.stringify({coreStartMs:2000,report:{environment:{review:{groups:[group]}}}})));
  assert.equal(old.review.groups.length,0);
});
test("damaged archive row is identified without hiding other readable feedback",async()=>{
  const result=await archiveReviewPreview(chunks("{bad}\n"+JSON.stringify({report:{environment:{review:{groups:[group]}}}})));
  assert.equal(result.preview.limited,true);assert.equal(result.review.groups.length,1);
});
