package org.eowoo.routy;

import static org.junit.Assert.*;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SafeAreaTest {
    private String js(ActivityScenario<MainActivity> scenario, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        scenario.onActivity(a -> a.getBridge().getWebView().evaluateJavascript(script, value -> { result.set(value); done.countDown(); }));
        assertTrue("WebView script timed out", done.await(5, TimeUnit.SECONDS));
        return result.get();
    }

    private void waitFor(ActivityScenario<MainActivity> scenario, String condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if ("true".equals(js(scenario, "Boolean(" + condition + ")"))) return;
            SystemClock.sleep(250);
        }
        fail("WebView condition timed out: " + condition);
    }

    private void check(ActivityScenario<MainActivity> scenario, String name, boolean keyboard) throws Exception {
        String script = "JSON.stringify({width:innerWidth,controls:Array.from(document.querySelectorAll('.header a,.header button,.mobile-nav a'))"
            + ".filter(e=>e.getBoundingClientRect().width>0).map(e=>{const r=e.getBoundingClientRect();return {text:e.textContent,left:r.left,right:r.right,top:r.top,bottom:r.bottom}})})";
        JSONObject data = new JSONObject((String)new JSONTokener(js(scenario, script)).nextValue());
        scenario.onActivity(a -> {
            try {
                android.webkit.WebView web = a.getBridge().getWebView();
                int[] location = new int[2]; web.getLocationOnScreen(location);
                Rect bounds = a.getWindowManager().getCurrentWindowMetrics().getBounds();
                WindowInsetsCompat all = ViewCompat.getRootWindowInsets(a.getWindow().getDecorView());
                assertNotNull(all);
                Insets bars = all.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                int bottom = keyboard ? Math.max(bars.bottom, all.getInsets(WindowInsetsCompat.Type.ime()).bottom) : bars.bottom;
                if (keyboard) assertTrue("Keyboard did not open", all.isVisible(WindowInsetsCompat.Type.ime()));
                double scale = web.getWidth()/data.getDouble("width");
                org.json.JSONArray controls = data.getJSONArray("controls");
                assertTrue("No rendered controls", controls.length()>0);
                for (int i=0;i<controls.length();i++) {
                    JSONObject r=controls.getJSONObject(i);
                    assertTrue(name+": control overlaps status bar "+r,location[1]+r.getDouble("top")*scale >= bars.top-2);
                    assertTrue(name+": control overlaps bottom system area "+r,location[1]+r.getDouble("bottom")*scale <= bounds.height()-bottom+2);
                    assertTrue(name+": control overlaps left cutout "+r,location[0]+r.getDouble("left")*scale >= bars.left-2);
                    assertTrue(name+": control overlaps right cutout "+r,location[0]+r.getDouble("right")*scale <= bounds.width()-bars.right+2);
                }
                Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                File output=new File(a.getExternalCacheDir(),"safe-area-"+name+".png");
                try(FileOutputStream stream=new FileOutputStream(output)){screenshot.compress(Bitmap.CompressFormat.PNG,100,stream);}
                screenshot.recycle();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test public void controlsStayInsideSystemBarsAndKeyboard() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitFor(scenario,"document.querySelector('.header .brand') && !document.querySelector('.header button:disabled')");
            SystemClock.sleep(1000); // Allow native insets and CSS delivery to settle after first paint.
            check(scenario,"portrait",false);
            scenario.onActivity(a->a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            waitFor(scenario,"innerWidth>innerHeight"); SystemClock.sleep(750);
            check(scenario,"landscape",false);
            scenario.onActivity(a->a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            waitFor(scenario,"innerHeight>innerWidth");
            js(scenario,"document.querySelector('.header button').click()");
            waitFor(scenario,"document.querySelector('input[name=email]')");
            js(scenario,"document.querySelector('input[name=email]').focus()");
            scenario.onActivity(a->{a.getBridge().getWebView().requestFocus();((InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(a.getBridge().getWebView(),InputMethodManager.SHOW_IMPLICIT);});
            SystemClock.sleep(1500);
            check(scenario,"keyboard",true);
        }
    }
}
