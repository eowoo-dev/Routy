"""Boot an available notched iPhone and an iPad and capture the real WKWebView UI."""
import json, os, pathlib, subprocess, time

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
for label,device in [("iphone",iphone),("ipad",ipad)]:
    udid=device["udid"]
    print("Checking",label,device["name"],flush=True)
    try:
        if device["state"]!="Booted":run("boot",udid)
        run("bootstatus",udid,"-b")
        run("status_bar",udid,"override","--time","09:41","--dataNetwork","wifi","--wifiMode","active","--wifiBars","3","--batteryState","charged","--batteryLevel","100")
        run("install",udid,str(app))
        run("launch",udid,"org.eowoo.routy")
        time.sleep(10)
        run("io",udid,"screenshot",str(output/f"{label}-portrait.png"))
        (output/f"{label}-device.json").write_text(json.dumps(device,indent=2))
    finally:
        # Do not block the next device behind CoreSimulator's occasionally slow shutdown.
        subprocess.Popen(["xcrun","simctl","shutdown",udid],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
print("Captured iPhone and iPad WKWebView safe areas for visual review.")
