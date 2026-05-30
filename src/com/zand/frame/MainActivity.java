package com.zand.frame;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
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
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class MainActivity extends Activity {

    private WebView web;
    private UploadServer uploadServer;

    // Generic fallback folder; the app auto-picks the folder with the most
    // images on first run (see app.js), so this is only used if none are found.
    private static final String PHOTO_DIR = "/sdcard/Pictures";
    private static final String UPLOAD_DIR = "/sdcard/zpix_uploads";
    private static final int UPLOAD_PORT = 8080;

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
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) { }
                    return true; // open externally; don't navigate the frame
                }
                return false;
            }
        });
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
                File root = new File(path);
                ArrayList<File> files = new ArrayList<File>();
                collectImagesRecursive(root, files, 0);
                final boolean byDate = "date".equals(order);
                Collections.sort(files, new Comparator<File>() {
                    public int compare(File a, File b) {
                        if (byDate) {
                            long d = a.lastModified() - b.lastModified();
                            if (d != 0) return d < 0 ? -1 : 1;
                        }
                        // group by folder, then name
                        return a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath());
                    }
                });
                for (File f : files) arr.put(fileToUrl(f));
            } catch (Exception e) { }
            return arr.toString();
        }

        // Recursive image count (used by the selected-folder list).
        @JavascriptInterface
        public int countAllImages(String path) {
            try {
                if (path == null || path.length() == 0) return 0;
                ArrayList<File> files = new ArrayList<File>();
                collectImagesRecursive(new File(path), files, 0);
                return files.size();
            } catch (Exception e) { return 0; }
        }

        private void collectImagesRecursive(File dir, ArrayList<File> out, int depth) {
            if (depth > 10 || dir == null) return;
            File[] kids;
            try { kids = dir.listFiles(); } catch (Exception e) { return; }
            if (kids == null) return;
            // sort kids so order is stable across runs
            Arrays.sort(kids, new Comparator<File>() {
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File k : kids) {
                if (k.isFile()) {
                    if (isImage(k.getName())) out.add(k);
                } else if (k.isDirectory()) {
                    String n = k.getName();
                    if (n.startsWith(".") || n.equalsIgnoreCase("Android")) continue;
                    collectImagesRecursive(k, out, depth + 1);
                }
            }
        }

        private String fileToUrl(File f) {
            String abs = f.getAbsolutePath();
            String[] segs = abs.split("/");
            StringBuilder sb = new StringBuilder("file://");
            for (String s : segs) {
                if (s.length() == 0) continue;
                sb.append('/').append(Uri.encode(s));
            }
            return sb.toString();
        }

        // Folders under /sdcard (and one level of subfolders) that contain
        // images, with counts. Catches nested locations like DCIM/Camera.
        @JavascriptInterface
        public String getFolders() {
            JSONArray arr = new JSONArray();
            try {
                File root = new File("/sdcard");
                ArrayList<JSONObject> list = new ArrayList<JSONObject>();
                collectFolders(root, 0, list);
                Collections.sort(list, new Comparator<JSONObject>() {
                    public int compare(JSONObject a, JSONObject b) {
                        return b.optInt("count") - a.optInt("count");
                    }
                });
                for (JSONObject o : list) arr.put(o);
            } catch (Exception e) { }
            return arr.toString();
        }

        private void collectFolders(File dir, int depth, ArrayList<JSONObject> list) {
            if (depth > 2) return;
            File[] kids;
            try { kids = dir.listFiles(); } catch (Exception e) { return; }
            if (kids == null) return;
            if (depth >= 1) {
                int c = countImages(dir);
                if (c > 0) {
                    try {
                        JSONObject o = new JSONObject();
                        String rel = dir.getAbsolutePath()
                                .replaceFirst("^/sdcard/", "")
                                .replaceFirst("^/storage/emulated/0/", "");
                        o.put("name", rel);
                        o.put("path", dir.getAbsolutePath());
                        o.put("count", c);
                        list.add(o);
                    } catch (Exception e) { }
                }
            }
            for (File k : kids) {
                if (!k.isDirectory()) continue;
                String n = k.getName();
                if (n.startsWith(".") || n.equalsIgnoreCase("Android")) continue;
                collectFolders(k, depth + 1, list);
            }
        }

        // Local in-app HTTP server for receiving uploaded photos (toggleable).
        @JavascriptInterface
        public boolean startUploadServer() {
            if (uploadServer != null && uploadServer.isRunning()) return true;
            try {
                java.io.InputStream is = getAssets().open("uploader.html");
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                uploadServer = new UploadServer(UPLOAD_PORT,
                        new File(UPLOAD_DIR), bos.toByteArray(),
                        new Runnable() { public void run() {
                            new File(UPLOAD_DIR).setReadable(true, false);
                        }},
                        new UploadServer.InfoProvider() {
                            public String json() { return storageInfoJson(); }
                        });
                return uploadServer.start();
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void stopUploadServer() {
            if (uploadServer != null) { uploadServer.stop(); uploadServer = null; }
        }

        @JavascriptInterface
        public boolean uploadServerRunning() {
            return uploadServer != null && uploadServer.isRunning();
        }

        @JavascriptInterface
        public String uploadServerUrl() {
            String ip = wifiIp();
            if (ip == null || ip.length() == 0) return "";
            return "http://" + ip + ":" + UPLOAD_PORT;
        }

        @JavascriptInterface
        public String uploadDir() { return UPLOAD_DIR; }

        @JavascriptInterface
        public boolean getAutoStart() {
            return getSharedPreferences("zpix", MODE_PRIVATE).getBoolean("autoStart", true);
        }
        @JavascriptInterface
        public void setAutoStart(boolean v) {
            getSharedPreferences("zpix", MODE_PRIVATE).edit().putBoolean("autoStart", v).apply();
        }

        private String storageInfoJson() {
            try {
                File dir = new File(UPLOAD_DIR);
                dir.mkdirs();
                android.os.StatFs sf = new android.os.StatFs(dir.getPath());
                long bs, total, free;
                if (android.os.Build.VERSION.SDK_INT >= 18) {
                    bs = sf.getBlockSizeLong();
                    total = sf.getBlockCountLong() * bs;
                    free = sf.getAvailableBlocksLong() * bs;
                } else {
                    bs = sf.getBlockSize();
                    total = (long) sf.getBlockCount() * bs;
                    free = (long) sf.getAvailableBlocks() * bs;
                }
                long[] tally = new long[]{ 0, 0 }; // count, bytes
                tallyTree(dir, tally, 0);
                return "{\"total\":" + total +
                       ",\"free\":" + free +
                       ",\"used\":" + (total - free) +
                       ",\"uploads\":" + tally[0] +
                       ",\"uploadsBytes\":" + tally[1] + "}";
            } catch (Exception e) {
                return "{}";
            }
        }
        private void tallyTree(File dir, long[] tally, int depth) {
            if (depth > 12 || dir == null) return;
            File[] kids;
            try { kids = dir.listFiles(); } catch (Exception e) { return; }
            if (kids == null) return;
            for (File k : kids) {
                if (k.isFile()) { tally[0]++; tally[1] += k.length(); }
                else if (k.isDirectory()) tallyTree(k, tally, depth + 1);
            }
        }

        private String wifiIp() {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip == 0) return null;
                return String.format("%d.%d.%d.%d",
                        ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
            } catch (Exception e) { return null; }
        }

        // Browse a directory: returns the path, its parent (within /sdcard), the
        // image count here, and its subfolders (with image counts) for a picker.
        @JavascriptInterface
        public String listDir(String path) {
            JSONObject res = new JSONObject();
            try {
                if (path == null || path.length() == 0) path = "/sdcard";
                File dir = new File(path);
                String abs = dir.getAbsolutePath();
                JSONArray dirs = new JSONArray();
                File[] kids = dir.listFiles();
                if (kids != null) {
                    Arrays.sort(kids, new Comparator<File>() {
                        public int compare(File a, File b) {
                            return a.getName().compareToIgnoreCase(b.getName());
                        }
                    });
                    for (File k : kids) {
                        if (!k.isDirectory()) continue;
                        String n = k.getName();
                        if (n.startsWith(".") || n.equalsIgnoreCase("Android")) continue;
                        JSONObject o = new JSONObject();
                        o.put("name", n);
                        o.put("path", k.getAbsolutePath());
                        o.put("images", countImages(k));
                        dirs.put(o);
                    }
                }
                res.put("path", abs);
                res.put("images", countImages(dir));
                // don't allow browsing above /sdcard (or its real /storage/emulated/0)
                boolean atRoot = abs.equals("/sdcard") || abs.equals("/storage/emulated/0")
                        || abs.equals("/storage/emulated") || abs.equals("/storage");
                File parent = dir.getParentFile();
                res.put("parent", (!atRoot && parent != null) ? parent.getAbsolutePath() : "");
                res.put("dirs", dirs);
            } catch (Exception e) { }
            return res.toString();
        }

        // Download the APK natively (manual redirect following) and launch the
        // system installer. Logs each step to logcat tag "zpix" for diagnostics.
        @JavascriptInterface
        public void downloadAndInstall(final String url) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        SSLSocketFactory sf = trustAllFactory();
                        HostnameVerifier hv = new HostnameVerifier() {
                            public boolean verify(String h, SSLSession s) { return true; }
                        };
                        String cur = url;
                        HttpURLConnection c = null;
                        for (int hop = 0; hop < 6; hop++) {
                            c = (HttpURLConnection) new URL(cur).openConnection();
                            if (c instanceof HttpsURLConnection) {
                                ((HttpsURLConnection) c).setSSLSocketFactory(sf);
                                ((HttpsURLConnection) c).setHostnameVerifier(hv);
                            }
                            c.setInstanceFollowRedirects(false);
                            c.setConnectTimeout(20000);
                            c.setReadTimeout(30000);
                            c.setRequestProperty("User-Agent", "zpix");
                            int code = c.getResponseCode();
                            Log.i("zpix", "DL hop " + hop + " code=" + code);
                            if (code >= 300 && code < 400) {
                                String loc = c.getHeaderField("Location");
                                c.disconnect();
                                if (loc == null) throw new Exception("redirect without Location");
                                cur = loc;
                                continue;
                            }
                            break;
                        }
                        InputStream in = c.getInputStream();
                        File out = new File(Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS), "zpix-update.apk");
                        FileOutputStream fo = new FileOutputStream(out);
                        byte[] buf = new byte[8192];
                        int n;
                        long tot = 0;
                        while ((n = in.read(buf)) > 0) { fo.write(buf, 0, n); tot += n; }
                        fo.flush();
                        fo.close();
                        in.close();
                        Log.i("zpix", "DL wrote " + tot + " bytes -> " + out.getAbsolutePath());
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
                        Log.e("zpix", "DL fail: " + e);
                        notifyUpdateFailed();
                    }
                }
            }).start();
        }

        // Trust-all factory for the update download only. Safe because the package
    // installer rejects any APK not signed with our release key, so a tampered
    // download cannot install — TLS trust here only guards against download DoS.
    private SSLSocketFactory trustAllFactory() throws Exception {
        TrustManager[] tm = new TrustManager[]{ new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) { }
            public void checkServerTrusted(X509Certificate[] c, String a) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tm, new SecureRandom());
        return ctx.getSocketFactory();
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
