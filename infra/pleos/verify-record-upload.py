"""Local emulator integration: completed scenario -> durable queue -> authenticated API.
Open the debug app and connect scenario auto-analysis first. Never targets a physical car.
The offline check briefly stops only compose.dev.yaml's web service and restores it in finally.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
PACKAGE="com.noonmaru.routy.pleos.debug"

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial",default="emulator-5554")
    args=parser.parse_args()
    if not re.fullmatch(r"emulator-\d+",args.serial):raise ValueError("Only an emulator-NNNN serial is supported")
    adb=str(Path(os.environ["LOCALAPPDATA"])/"Android/Sdk/platform-tools/adb.exe")
    def shell(*values,check=True):
        result=subprocess.run([adb,"-s",args.serial,"shell",*values],capture_output=True,text=True,encoding="utf-8",check=check)
        return result.stdout
    user=shell("am","get-current-user").strip()
    def app_json(path):
        text=shell("run-as",PACKAGE,"--user",user,"cat",path,check=False)
        try:return json.loads(text)
        except json.JSONDecodeError:return None
    def local(session_id):return app_json(f"no_backup/driving-records/{session_id}.json")
    def request(port,path,method="GET",body=None,token=None):
        headers={"Content-Type":"application/json"}
        if token:headers["Authorization"]="Bearer "+token
        data=None if body is None else json.dumps(body,ensure_ascii=False).encode("utf-8")
        with urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{port}{path}",data=data,headers=headers,method=method),timeout=15) as response:
            return response.status,json.load(response)
    def wait(action,message,timeout=120):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            value=action()
            if value:return value
            time.sleep(.5)
        raise RuntimeError(message)
    def scenario(name):
        _,state=request(8090,"/routy/scenarios/control","POST",{"action":"select","id":name})
        session_id=state["frame"]["runId"]
        owned.add(session_id)
        request(8090,"/routy/scenarios/control","POST",{"action":"start"})
        wait(lambda:request(8090,"/routy/scenarios/state")[1]["status"]=="completed","Scenario did not complete",45)
        row=wait(lambda:local(session_id),"No queued record. Connect scenario auto-analysis in the debug app.",30)
        assert row["payload"]["origin"]=="SYNTHETIC"
        return session_id
    def compose(action):
        subprocess.run(["docker","compose","-f","compose.dev.yaml",action,"web"],cwd=ROOT,check=True,capture_output=True)
    def health():
        try:return request(3000,"/api/health")[0]==200
        except OSError:return False
    def acknowledged(session_id):
        row=local(session_id)
        return row if row and row["state"]=="SENT" else None
    results=[];stopped=False;owned=set()
    try:
        wait(health,"Development API is unavailable",30)
        online=scenario("following-gap")
        wait(lambda:acknowledged(online),"Online upload was not acknowledged",60)
        print("PASS completed scenario uploaded",flush=True)
        stopped=True;compose("stop")
        offline=[scenario("lane-change-interrupted"),scenario("lead-cut-in")]
        for session_id in offline:assert local(session_id)["state"]=="PENDING"
        print("PASS two offline records retained",flush=True)
        shell("am","force-stop","--user",user,PACKAGE)
        shell("am","start","-W","--user",user,"-a","android.intent.action.MAIN","-c","android.intent.category.LAUNCHER","-n",PACKAGE+"/com.noonmaru.routy.pleos.feature.trip.DrivingActivity")
        for session_id in offline:assert local(session_id)["state"]=="PENDING"
        print("PASS queue survived process restart",flush=True)
        compose("start");stopped=False
        wait(health,"Development API did not recover",60)
        for session_id in offline:wait(lambda:acknowledged(session_id),"Offline record was not retried",150)
        credentials=app_json("no_backup/vehicle-installation.json")
        token=credentials["installationId"]+"."+credentials["secret"]
        for session_id in [online,*offline]:
            row=local(session_id)
            status,detail=request(3000,f"/api/driving-records/{session_id}",token=token)
            assert status==200 and detail["report"]==row["payload"]["report"]
            status,receipt=request(3000,f"/api/driving-records/{session_id}","PUT",row["payload"],token)
            assert status==200 and receipt["id"]==row["receipt"]["id"]
            results.append({"sessionId":session_id,"recordId":receipt["id"],"passed":True})
        _,page=request(3000,"/api/driving-records?limit=50",token=token)
        for session_id in [online,*offline]:assert sum(r["sessionId"]==session_id for r in page["items"])==1
        interrupted=local(offline[0])["payload"]["report"]["environment"]["events"]
        assert next(e for e in interrupted if e["kind"]=="INTERRUPTED_LANE_CHANGE_SIGNAL")["evidence"]["signalOffDurationMs"]==500
        print("PASS automatic recovery, full report preservation and idempotent retries",flush=True)
        output=ROOT/"apps/pleos/driving-evaluation/build/scenarios/upload/result.json"
        output.parent.mkdir(parents=True,exist_ok=True)
        output.write_text(json.dumps({"passed":True,"records":results},indent=2),encoding="utf-8")
    finally:
        if stopped:compose("start")
        try:
            _,state=request(8090,"/routy/scenarios/state")
            if (state.get("frame") or {}).get("runId") in owned:
                request(8090,"/routy/scenarios/control","POST",{"action":"stop"})
                request(8090,"/routy/scenarios/control","POST",{"action":"release"})
        except OSError:pass

if __name__=="__main__":main()
