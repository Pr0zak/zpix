package com.zand.frame;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
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
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i("zpix", m.message());
                return true;
            }
        });
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
        public String getPhotos(String path, String order) {
            JSONArray arr = new JSONArray();
            try {
                if (path == null || path.length() == 0) path = PHOTO_DIR;
                File dir = new File(path);
                File[] files = dir.listFiles();
                if (files != null) {
                    final boolean byDate = "date".equals(order);
                    Arrays.sort(files, new Comparator<File>() {
                        public int compare(File a, File b) {
                            if (byDate) {
                                long d = a.lastModified() - b.lastModified(); // oldest -> newest
                                if (d != 0) return d < 0 ? -1 : 1;
                            }
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

        // The WebView (modern TLS) downloads the APK and passes the bytes here as
        // base64; we write them out and launch the system installer. Done this way
        // because the device's native TLS stack is too old for GitHub's CDN.
        @JavascriptInterface
        public void installBytes(final String b64) {
            try {
                byte[] data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                File out = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "zpix-update.apk");
                FileOutputStream fo = new FileOutputStream(out);
                fo.write(data);
                fo.flush();
                fo.close();
                final Uri uri = Uri.fromFile(out);
                runOnUiThread(new Runnable() {
                    public void run() {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(uri, "application/vnd.android.package-archive");
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    }
                });
            } catch (Exception e) {
                notifyUpdateFailed();
            }
        }

        private void notifyUpdateFailed() {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (web != null) web.loadUrl("javascript:window.zpixUpdateFailed && window.zpixUpdateFailed();");
                }
            });
        }
    }
}
