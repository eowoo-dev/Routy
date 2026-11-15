"""Native release smoke test on a local Pleos emulator only. Leaves Park/0 and closes its console lease."""
import argparse
import json
import hashlib
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
PACKAGE="org.eowoo.routy.pleos"

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--serial",default="emulator-5554")
    args=parser.parse_args()
    if not re.fullmatch(r"emulator-\d+",args.serial):raise SystemExit("Only local Android emulators are supported")
    sdk=Path(os.environ.get("ANDROID_HOME",Path(os.environ["LOCALAPPDATA"])/"Android/Sdk"))
    adb=str(sdk/"platform-tools/adb.exe")
    prefix=[adb,"-s",args.serial]
    output=ROOT/"apps/pleos/app/build/reports/submission-smoke"
    output.mkdir(parents=True,exist_ok=True)
    (output/"result.json").unlink(missing_ok=True)
    def shell(*values):return subprocess.check_output(prefix+["shell",*map(str,values)],text=True,encoding="utf-8",timeout=35)
    def request(path,method="GET",**values):
        suffix="?"+urllib.parse.urlencode(values) if values else ""
        with urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8088"+path+suffix,method=method),timeout=5) as response:return json.load(response)
    def tree():
        shell("uiautomator","dump","/sdcard/routy-submission.xml")
        return ET.fromstring(shell("cat","/sdcard/routy-submission.xml"))
    def texts():return [n.get("text","") for n in tree().iter("node")]
    def find(text,scroll=0):
        for attempt in range(abs(scroll)+1):
            root=tree()
            for n in root.iter("node"):
                if text in n.get("text",""):return n
            if attempt<abs(scroll):
                shell("input","swipe",1900,950 if scroll>0 else 500,1900,500 if scroll>0 else 950,450)
        raise AssertionError("Control not found: "+text)
    def tap(node):
        x1,y1,x2,y2=map(int,re.findall(r"\d+",node.get("bounds")))
        shell("input","tap",(x1+x2)//2,(y1+y2)//2)
    def top():
        for _ in range(4):shell("input","swipe",1900,300,1900,1100,100)
    def screenshot(name):
        (output/f"{name}.png").write_bytes(subprocess.check_output(prefix+["exec-out","screencap","-p"],timeout=20))
    subprocess.run(prefix+["forward","tcp:8088","tcp:8088"],check=True)
    client="routy-submission-smoke"
    stopped=threading.Event();heartbeat_errors=[];heartbeat_thread=None
    def lease():
        value=request("/browser/session","POST",client=client)
        if value.get("clients")!=1:raise RuntimeError("Close Virtual Console tabs so this smoke test has exclusive simulator input")
    def heartbeat():
        while not stopped.wait(1):
            try:lease()
            except Exception as e:heartbeat_errors.append(str(e));return
    try:
        lease();heartbeat_thread=threading.Thread(target=heartbeat,daemon=True);heartbeat_thread.start()
        request("/vehicle/controls","PUT",speed=0,accelerator=0,brake=0,steering=0)
        request("/vehicle/gear","PUT",gear="Park")
        subprocess.run(prefix+["install","-r",str(ROOT/"apk/어쩌다우리_루티_release.apk")],check=True)
        if "TripService" in shell("dumpsys","activity","services",PACKAGE):
            raise RuntimeError("Turn off actual automatic recording and finish any active practice before testing")
        shell("am","force-stop",PACKAGE)
        shell("am","start","-W","-a","android.intent.action.MAIN","-c","android.intent.category.LAUNCHER","-n",PACKAGE+"/org.eowoo.routy.pleos.feature.trip.DrivingActivity")
        time.sleep(2)
        tap(find("합성 시연"))
        assert "합성 데이터 시연 · 실제 주행 평가 아님" in texts()
        screenshot("ready")
        # Verify pause/reset before any complete result can be published.
        start=find("합성 시연 시작",scroll=4)
        pause=find("시연 정지")
        tap(start);time.sleep(1);tap(pause)
        assert any("일시 정지" in t for t in texts())
        tap(find("시연 초기화"))
        assert any("재생 0.0 /" in t for t in texts())
        results=[]
        for index,(name,duration) in enumerate([("정상 차로 변경",12),("차로 경계 접근",12),("적색 신호 정차",14),("앞차 간격 감소",10),("중간 해제 후 재점등",12)]):
            top()
            if index==4:tap(find("실패·경계 사례 선택 (9개)"))
            tap(find(name));tap(find("합성 시연 시작",scroll=4))
            time.sleep(duration+3)
            assert not heartbeat_errors,heartbeat_errors
            assert any("완료" in t and "재생" in t for t in texts()),name+": did not complete"
            assert any("기기에 저장" in t for t in texts()),name+": local save did not succeed"
            find("합성 데이터 결과 · 실제 주행 평가 아님",scroll=5)
            screenshot(name)
            results.append({"scenario":name,"completed":True,"syntheticLabel":True})
            print("PASS",name,flush=True)
        flags=re.findall(r"(?:pkgFlags|flags)=\[([^\]]+)\]",shell("dumpsys","package",PACKAGE))
        assert flags and all("DEBUGGABLE" not in value for value in flags)
        sha256=hashlib.sha256((ROOT/"apk/어쩌다우리_루티_release.apk").read_bytes()).hexdigest()
        (output/"result.json").write_text(json.dumps({"package":PACKAGE,"apkSha256":sha256,"cases":results,"pauseReset":True,"debuggable":False},ensure_ascii=False,indent=2),encoding="utf-8")
    finally:
        stopped.set()
        if heartbeat_thread:heartbeat_thread.join(timeout=6)
        request("/browser/session","POST",client=client,closing=1)

if __name__=="__main__":main()
