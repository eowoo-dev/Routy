"""Check real WebView controls against Android system and IME insets in both navigation modes."""
import pathlib, re, subprocess

root=pathlib.Path(__file__).resolve().parents[2]
output=root/"build/safe-area/android"
def adb(*args):
    return subprocess.check_output(["adb",*args],text=True,stderr=subprocess.STDOUT)
for apk in ["app/build/outputs/apk/debug/app-debug.apk","app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
    print(adb("install","-r",str(root/"apps/web/android"/apk)))
adb("shell","settings","put","secure","show_ime_with_hard_keyboard","1")
for mode in ["gestural","threebutton"]:
    folder=output/mode;folder.mkdir(parents=True,exist_ok=True)
    adb("shell","cmd","overlay","enable-exclusive","--category",f"com.android.internal.systemui.navbar.{mode}")
    adb("shell","am","force-stop","org.eowoo.routy")
    adb("shell","rm","-f",*[f"/sdcard/Android/data/org.eowoo.routy/cache/safe-area-{name}.png" for name in ["portrait","landscape","keyboard"]])
    result=adb("shell","am","instrument","-w","-r","-e","class","org.eowoo.routy.SafeAreaTest","org.eowoo.routy.test/androidx.test.runner.AndroidJUnitRunner")
    (folder/"instrumentation.txt").write_text(result)
    print(result[-2500:])
    for name in ["portrait","landscape","keyboard"]:
        subprocess.run(["adb","pull",f"/sdcard/Android/data/org.eowoo.routy/cache/safe-area-{name}.png",str(folder)],check=False)
    if not re.search(r"OK \(\d+ tests?\)",result) or "FAILURES!!!" in result:
        raise SystemExit(f"Safe-area instrumentation failed in {mode} mode")
print("PASS Android portrait, landscape and keyboard in gesture and three-button modes.")
