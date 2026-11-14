// Bounded compatibility reader for archives written before review previews were retained.
type Json=Record<string,unknown>;
const object=(v:unknown):Json=>v!==null && typeof v==="object" && !Array.isArray(v)?v as Json:{};
const rows=(v:unknown):Json[]=>Array.isArray(v)?v.map(object):[];
const MAX_LINE=2_097_152,MAX_SCAN=33_554_432,LIMIT=32;
export async function archiveReviewPreview(chunks:AsyncIterable<Uint8Array>) {
  const decoder=new TextDecoder();let pending="",bytes=0,limited=false;
  const groups:Json[]=[];
  function line(text:string) {
    if(!text.trim())return;
    let value:Json;try{value=object(JSON.parse(text));}catch{limited=true;return;}
    const report=object(value.report),review=object(object(report.environment).review);
    for(const g of rows(review.groups)) {
      if(typeof g.startMs!=="number" || typeof g.endMs!=="number" || !Number.isFinite(g.startMs) || !Number.isFinite(g.endMs) || g.endMs<g.startMs || typeof g.title!=="string" || typeof g.nextPractice!=="string")continue;
      if(typeof value.coreStartMs==="number" && g.endMs<=value.coreStartMs)continue;
      const kinds=Array.isArray(g.kinds)?g.kinds.filter(k=>typeof k==="string"):[];
      const prior=groups.find(p=>p.title===g.title && JSON.stringify(p.kinds)===JSON.stringify(kinds) && Number(p.startMs)<=Number(g.endMs) && Number(p.endMs)>=Number(g.startMs));
      if(prior){prior.startMs=Math.min(Number(prior.startMs),g.startMs);prior.endMs=Math.max(Number(prior.endMs),g.endMs);prior.limited=true;continue;}
      if(groups.length>=LIMIT){limited=true;continue;}
      groups.push({...g,kinds,id:`archive-${g.startMs}-${g.endMs}-${groups.length}`,limited:true});
    }
  }
  for await(const chunk of chunks) {
    bytes+=chunk.byteLength;
    if(bytes>MAX_SCAN){limited=true;break;}
    pending+=decoder.decode(chunk,{stream:true});
    let at:number;
    while((at=pending.indexOf("\n"))>=0) {
      if(at>MAX_LINE){limited=true;}else line(pending.slice(0,at));
      pending=pending.slice(at+1);
    }
    if(pending.length>MAX_LINE){limited=true;break;}
  }
  pending+=decoder.decode();
  if(pending.length<=MAX_LINE)line(pending);else limited=true;
  return {review:{groups:groups.sort((a,b)=>Number(a.startMs)-Number(b.startMs)),quality:[],issues:[],metrics:{}},
    preview:{source:"LEGACY_ARCHIVE",limit:LIMIT,limited,quality:"PER_WINDOW_ONLY"}};
}
