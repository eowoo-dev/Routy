"""Boot an available notched iPhone and an iPad and capture the real WKWebView UI."""
import json, os, pathlib, subprocess, time
from PIL import Image

root=pathlib.Path(__file__).resolve().parents[2]
output=root/"build/safe-area/ios"
output.mkdir(parents=True,exist_ok=True)
def run(*args):
    print("simctl",*args,flush=True)
    return subprocess.check_output(["xcrun","simctl",*args],text=True,timeout=180)
devices=json.loads(run("list","devices","available","--json"))["devices"]
available=[d for runtime,rows in devices.items() if "iOS" in runtime for d in rows if d.get("isAvailable")]
iphone=next((d for d in available if "iPhone" in d["name"] and "Pro" in d["name"]),None)
ipad=next((d for d in available if "iPad Pro" in d["name"]),None)
if not iphone or not ipad: raise SystemExit("An available iPhone Pro and iPad Pro simulator are required")
app=root/"apps/web/ios/build/simulator/Build/Products/Debug-iphonesimulator/App.app"
def app_is_visible(path):
    # Reject boot logos and SpringBoard rather than treating any PNG as a successful app launch.
    with Image.open(path) as source:
        preview=source.convert("RGB").resize((180,360))
        pixels=list(preview.getdata())
        green=sum(1 for r,g,b in pixels if 40<g<180 and r<g*.65 and b<g)
        background=sum(1 for r,g,b in pixels if abs(r-242)<12 and abs(g-245)<12 and abs(b-241)<12)
        return green>35 and background>len(pixels)*.12
for label,device in [("iphone",iphone),("ipad",ipad)]:
    udid=device["udid"]
    print("Checking",label,device["name"],flush=True)
    try:
        if device["state"]!="Booted":run("boot",udid)
        subprocess.run(["open","-a","Simulator","--args","-CurrentDeviceUDID",udid],check=True,timeout=30)
        run("bootstatus",udid,"-b")
        run("status_bar",udid,"override","--time","09:41","--dataNetwork","wifi","--wifiMode","active","--wifiBars","3","--batteryState","charged","--batteryLevel","100")
        run("install",udid,str(app))
        run("launch",udid,"org.eowoo.routy")
        screenshot=output/f"{label}-portrait.png"
        deadline=time.monotonic()+180
        while True:
            run("io",udid,"screenshot",str(screenshot))
            if app_is_visible(screenshot): break
            if time.monotonic()>deadline: raise SystemExit(f"{label}: Routy UI did not become visible; screenshot is not valid app evidence")
            time.sleep(5)
            run("launch",udid,"org.eowoo.routy")
        (output/f"{label}-device.json").write_text(json.dumps(device,indent=2))
    finally:
        # Do not block the next device behind CoreSimulator's occasionally slow shutdown.
        subprocess.Popen(["xcrun","simctl","shutdown",udid],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
print("Captured visible Routy UI on iPhone and iPad for safe-area visual review.")
