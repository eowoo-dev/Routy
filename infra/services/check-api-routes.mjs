// No credentials or writes: every protected route must be registered and return JSON 401.
const base=process.env.RECORDS_TEST_API??"http://127.0.0.1:3000";
const id="0ed1953c-47de-4b21-8c05-d2f61e885201";
for(let attempt=0;;attempt++){
  try{if((await fetch(base+"/api/health",{signal:AbortSignal.timeout(20000)})).ok)break;}catch{}
  if(attempt>=20)throw new Error("API did not become ready");
  await new Promise(resolve=>setTimeout(resolve,1000));
}
for(const [path,method] of [
  [`/api/driving-records/${id}/parts/0`,"PUT"],
  [`/api/driving-records/${id}/complete`,"POST"],
  [`/api/driving-records/${id}/archive`,"GET"],
  [`/api/account/driving-records/${id}`,"GET"],
  [`/api/account/driving-records/${id}/archive`,"GET"],
  ["/api/account/pairings/preview","POST"],
]){
  const response=await fetch(base+path,{method,headers:{"Content-Type":"application/json"},body:method==="GET"?undefined:"{}",signal:AbortSignal.timeout(30000)});
  if(response.status!==401 || !response.headers.get("content-type")?.includes("application/json"))throw new Error(`Cold-start route missing: ${method} ${path} returned ${response.status}`);
  console.log("PASS cold-start route",method,path);
}
