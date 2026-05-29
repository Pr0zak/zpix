package com.zand.frame;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

public class MainActivity extends Activity {

    private WebView web;

    // Generic fallback folder; the app auto-picks the folder with the most
    // images on first run (see app.js), so this is only used if none are found.
    private static final String PHOTO_DIR = "/sdcard/Pictures";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setLoadsImagesAutomatically(true);
        s.setDomStorageEnabled(true);
        web.setBackgroundColor(0xFF000000);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");

        setContentView(web);
        hideSystemUi();
        web.loadUrl("file:///android_asset/index.html");
    }

    private void hideSystemUi() {
        View d = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        d.setSystemUiVisibility(flags);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    private static boolean isImage(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".bmp");
    }

    private static int countImages(File dir) {
        int c = 0;
        try {
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) {
                if (f.isFile() && isImage(f.getName())) c++;
            }
        } catch (Exception e) { }
        return c;
    }

    public class Bridge {
        @JavascriptInterface
        public String defaultFolder() { return PHOTO_DIR; }

        @JavascriptInterface
        public String getPhotos(String path) {
            JSONArray arr = new JSONArray();
            try {
                if (path == null || path.length() == 0) path = PHOTO_DIR;
                File dir = new File(path);
                File[] files = dir.listFiles();
                if (files != null) {
                    Arrays.sort(files, new Comparator<File>() {
                        public int compare(File a, File b) {
                            return a.getName().compareToIgnoreCase(b.getName());
                        }
                    });
                    String base = dir.getAbsolutePath();
                    for (File f : files) {
                        if (!f.isFile()) continue;
                        if (isImage(f.getName())) {
                            arr.put("file://" + base + "/" + Uri.encode(f.getName()));
                        }
                    }
                }
            } catch (Exception e) { }
            return arr.toString();
        }

        // Top-level folders under /sdcard that contain images, with counts.
        @JavascriptInterface
        public String getFolders() {
            JSONArray arr = new JSONArray();
            try {
                File root = new File("/sdcard");
                File[] dirs = root.listFiles();
                ArrayList<JSONObject> list = new ArrayList<JSONObject>();
                if (dirs != null) {
                    for (File d : dirs) {
                        if (!d.isDirectory()) continue;
                        String dn = d.getName();
                        if (dn.startsWith(".") || dn.equalsIgnoreCase("Android")) continue;
                        int c = countImages(d);
                        if (c > 0) {
                            JSONObject o = new JSONObject();
                            o.put("name", dn);
                            o.put("path", d.getAbsolutePath());
                            o.put("count", c);
                            list.add(o);
                        }
                    }
                }
                Collections.sort(list, new Comparator<JSONObject>() {
                    public int compare(JSONObject a, JSONObject b) {
                        return b.optInt("count") - a.optInt("count");
                    }
                });
                for (JSONObject o : list) arr.put(o);
            } catch (Exception e) { }
            return arr.toString();
        }
    }
}
