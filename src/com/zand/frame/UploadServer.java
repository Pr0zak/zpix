package com.zand.frame;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tiny HTTP server that lets any device on the wifi drop photos into a folder
 * on the tablet. Serves an HTML upload page at /, accepts multipart/form-data
 * POSTs at /upload.
 */
public class UploadServer {
    public interface InfoProvider { String json(); }

    private final int port;
    private final File uploadDir;
    private final byte[] htmlBytes;
    private final Runnable onUpload;
    private final InfoProvider infoProvider;
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;

    public UploadServer(int port, File uploadDir, byte[] htmlBytes, Runnable onUpload, InfoProvider infoProvider) {
        this.port = port;
        this.uploadDir = uploadDir;
        this.htmlBytes = htmlBytes;
        this.onUpload = onUpload;
        this.infoProvider = infoProvider;
    }

    public boolean isRunning() { return running; }
    public int getPort() { return port; }

    public boolean start() {
        if (running) return true;
        try {
            uploadDir.mkdirs();
            server = new ServerSocket(port);
            server.setReuseAddress(true);
            running = true;
            acceptThread = new Thread(new Runnable() {
                public void run() { acceptLoop(); }
            }, "zpix-upload-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            return true;
        } catch (IOException e) {
            running = false;
            return false;
        }
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
    }

    private void acceptLoop() {
        while (running) {
            final Socket cli;
            try { cli = server.accept(); }
            catch (IOException e) { if (running) continue; else break; }
            Thread t = new Thread(new Runnable() {
                public void run() { handle(cli); }
            }, "zpix-upload-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void handle(Socket cli) {
        try {
            cli.setSoTimeout(60000);
            BufferedInputStream in = new BufferedInputStream(cli.getInputStream(), 16384);
            OutputStream out = cli.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) { try { cli.close(); } catch (IOException e) {} return; }
            Map<String, String> headers = new HashMap<String, String>();
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(),
                                          line.substring(colon + 1).trim());
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { write(out, 400, "Bad Request", "text/plain", "Bad request".getBytes("UTF-8")); cli.close(); return; }
            String method = parts[0], path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            if ("GET".equals(method) && (path.equals("/") || path.equals("/index.html"))) {
                write(out, 200, "OK", "text/html; charset=utf-8", htmlBytes);
            } else if ("GET".equals(method) && path.equals("/info")) {
                String json = infoProvider != null ? infoProvider.json() : "{}";
                write(out, 200, "OK", "application/json", json.getBytes("UTF-8"));
            } else if ("GET".equals(method) && path.equals("/browse")) {
                String relPath = queryParam(parts[1], "path");
                write(out, 200, "OK", "application/json", browseJson(relPath).getBytes("UTF-8"));
            } else if ("GET".equals(method) && path.equals("/file")) {
                String relPath = queryParam(parts[1], "path");
                serveFile(out, relPath);
            } else if ("POST".equals(method) && path.equals("/upload")) {
                int saved = handleUpload(in, headers);
                if (saved > 0 && onUpload != null) onUpload.run();
                byte[] body = ("{\"saved\":" + saved + "}").getBytes("UTF-8");
                write(out, 200, "OK", "application/json", body);
            } else if ("POST".equals(method) && path.equals("/mkdir")) {
                byte[] resp = handleMkdir(readBody(in, headers));
                write(out, 200, "OK", "application/json", resp);
            } else if ("POST".equals(method) && path.equals("/rename")) {
                byte[] resp = handleRename(readBody(in, headers));
                write(out, 200, "OK", "application/json", resp);
            } else if ("POST".equals(method) && path.equals("/delete")) {
                byte[] resp = handleDelete(readBody(in, headers));
                write(out, 200, "OK", "application/json", resp);
            } else {
                write(out, 404, "Not Found", "text/plain", "Not found".getBytes("UTF-8"));
            }
            cli.close();
        } catch (Exception e) {
            try { cli.close(); } catch (IOException ignored) {}
        }
    }

    private int handleUpload(InputStream in, Map<String, String> headers) throws IOException {
        String ct = headers.get("content-type");
        if (ct == null) return 0;
        if (!ct.toLowerCase().contains("multipart/form-data")) return 0;
        int bIdx = ct.toLowerCase().indexOf("boundary=");
        if (bIdx < 0) return 0;
        String boundary = ct.substring(bIdx + 9).trim();
        if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
            boundary = boundary.substring(1, boundary.length() - 1);
        }
        int sc = boundary.indexOf(';');
        if (sc >= 0) boundary = boundary.substring(0, sc).trim();

        byte[] delim = ("--" + boundary).getBytes("UTF-8");
        byte[] delimCrlf = ("\r\n--" + boundary).getBytes("UTF-8");

        if (!skipUntilDelim(in, delim)) return 0;

        int saved = 0;
        while (true) {
            // After a boundary marker, either "--" (end) or CRLF (next part).
            int a = in.read();
            int b = in.read();
            if (a == -1 || b == -1) break;
            if (a == '-' && b == '-') break;
            // expect CRLF (a='\r', b='\n')

            // Part headers
            Map<String, String> partHdrs = new HashMap<String, String>();
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon > 0) partHdrs.put(line.substring(0, colon).trim().toLowerCase(),
                                            line.substring(colon + 1).trim());
            }

            String disp = partHdrs.get("content-disposition");
            String filename = extractFilename(disp);

            File outFile = null;
            OutputStream fout;
            if (filename != null) {
                String rel = sanitizeRelPath(filename);
                if (rel != null) {
                    File target = new File(uploadDir, rel);
                    File parent = target.getParentFile();
                    if (parent != null) parent.mkdirs();
                    outFile = uniqueFile(target);
                    fout = new BufferedOutputStream(new FileOutputStream(outFile), 16384);
                } else {
                    fout = new ByteArrayOutputStream();
                }
            } else {
                fout = new ByteArrayOutputStream(); // form field, drop it
            }

            boolean haveEnd = writeUntilDelim(in, delimCrlf, fout);
            fout.close();

            if (outFile != null && outFile.length() > 0) saved++;
            if (!haveEnd) break;
        }
        return saved;
    }

    private boolean skipUntilDelim(InputStream in, byte[] delim) throws IOException {
        int matched = 0;
        int c;
        while ((c = in.read()) != -1) {
            if ((byte) c == delim[matched]) {
                matched++;
                if (matched == delim.length) return true;
            } else if ((byte) c == delim[0]) {
                matched = 1;
            } else {
                matched = 0;
            }
        }
        return false;
    }

    private boolean writeUntilDelim(InputStream in, byte[] delim, OutputStream out) throws IOException {
        int dlen = delim.length;
        byte[] buf = new byte[dlen];
        int matched = 0;
        int c;
        while ((c = in.read()) != -1) {
            if ((byte) c == delim[matched]) {
                buf[matched++] = (byte) c;
                if (matched == dlen) return true;
            } else {
                if (matched > 0) {
                    out.write(buf, 0, matched);
                    matched = 0;
                }
                if ((byte) c == delim[0]) {
                    buf[matched++] = (byte) c;
                } else {
                    out.write(c);
                }
            }
        }
        if (matched > 0) out.write(buf, 0, matched);
        return false;
    }

    private static String extractFilename(String disp) {
        if (disp == null) return null;
        int idx = disp.toLowerCase().indexOf("filename=");
        if (idx < 0) return null;
        String rest = disp.substring(idx + 9).trim();
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            if (end < 0) return null;
            return rest.substring(1, end);
        }
        int end = rest.indexOf(';');
        return end < 0 ? rest.trim() : rest.substring(0, end).trim();
    }

    private static String sanitizeComponent(String c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            boolean ok = ch == '.' || ch == '-' || ch == '_' || ch == ' '
                    || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9');
            sb.append(ok ? ch : '_');
        }
        return sb.toString().trim();
    }

    // Sanitize a multipart filename that may include a relative path
    // ("vacation/IMG_001.jpg"). Each component is cleaned, "." / ".." are
    // dropped to prevent escaping uploadDir.
    private static String sanitizeRelPath(String name) {
        String[] segs = name.replace('\\', '/').split("/");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            String s = segs[i].trim();
            if (s.length() == 0 || s.equals(".") || s.equals("..")) continue;
            String safe = sanitizeComponent(s);
            if (safe.length() == 0) continue;
            if (out.length() > 0) out.append('/');
            out.append(safe);
        }
        if (out.length() == 0) return "photo.jpg";
        // last segment is the file name; if it has no extension just leave it as-is
        return out.toString();
    }

    private File uniqueFile(File target) {
        if (!target.exists()) return target;
        String name = target.getName();
        File parent = target.getParentFile();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String ext = dot >= 0 ? name.substring(dot) : "";
        for (int i = 1; i < 100000; i++) {
            File alt = new File(parent, base + "_" + i + ext);
            if (!alt.exists()) return alt;
        }
        return target;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                byte[] b = bos.toByteArray();
                int len = b.length;
                if (len > 0 && b[len - 1] == '\r') len--;
                return new String(b, 0, len, "UTF-8");
            }
            bos.write(c);
            if (bos.size() > 16384) break; // safety
        }
        return bos.size() > 0 ? bos.toString("UTF-8") : null;
    }

    // ---------- browse / edit helpers ----------

    // Resolve a relative path to a File under uploadDir. Returns null on escape.
    private File resolveSafe(String rel) {
        if (rel == null) rel = "";
        try { rel = URLDecoder.decode(rel, "UTF-8"); } catch (Exception e) {}
        String[] segs = rel.replace('\\', '/').split("/");
        StringBuilder sb = new StringBuilder();
        for (String s : segs) {
            if (s.length() == 0 || s.equals(".") || s.equals("..")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(s);
        }
        File f = sb.length() == 0 ? uploadDir : new File(uploadDir, sb.toString());
        try {
            String fc = f.getCanonicalPath();
            String uc = uploadDir.getCanonicalPath();
            if (fc.equals(uc) || fc.startsWith(uc + "/") || fc.startsWith(uc + File.separator)) return f;
        } catch (IOException e) { }
        return null;
    }

    private String queryParam(String requestPath, String name) {
        int q = requestPath.indexOf('?');
        if (q < 0) return null;
        String qs = requestPath.substring(q + 1);
        for (String p : qs.split("&")) {
            int eq = p.indexOf('=');
            if (eq < 0) continue;
            if (p.substring(0, eq).equals(name)) return p.substring(eq + 1);
        }
        return null;
    }

    private String relativeToUploadDir(File f) throws IOException {
        String fc = f.getCanonicalPath();
        String uc = uploadDir.getCanonicalPath();
        if (fc.equals(uc)) return "";
        if (fc.startsWith(uc + File.separator)) return fc.substring(uc.length() + 1).replace(File.separatorChar, '/');
        return fc;
    }

    private String browseJson(String relPath) {
        try {
            File dir = resolveSafe(relPath);
            if (dir == null || !dir.exists() || !dir.isDirectory()) return "{\"error\":\"bad path\"}";
            JSONObject res = new JSONObject();
            String rel = relativeToUploadDir(dir);
            res.put("path", rel);
            String parent = "";
            if (rel.length() > 0) {
                int s = rel.lastIndexOf('/');
                parent = s > 0 ? rel.substring(0, s) : "";
            }
            res.put("parent", parent);
            res.put("hasParent", rel.length() > 0);
            JSONArray items = new JSONArray();
            File[] kids = dir.listFiles();
            if (kids != null) {
                Arrays.sort(kids, new Comparator<File>() {
                    public int compare(File a, File b) {
                        if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                for (File k : kids) {
                    JSONObject o = new JSONObject();
                    o.put("name", k.getName());
                    o.put("isDir", k.isDirectory());
                    o.put("size", k.length());
                    o.put("mtime", k.lastModified());
                    o.put("path", rel.length() > 0 ? rel + "/" + k.getName() : k.getName());
                    if (k.isFile()) {
                        String ext = "";
                        int dot = k.getName().lastIndexOf('.');
                        if (dot >= 0) ext = k.getName().substring(dot + 1).toLowerCase();
                        o.put("isImage", ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png")
                                || ext.equals("gif") || ext.equals("webp") || ext.equals("bmp"));
                    } else {
                        int n = 0;
                        File[] gk = k.listFiles();
                        if (gk != null) n = gk.length;
                        o.put("count", n);
                    }
                    items.put(o);
                }
            }
            res.put("items", items);
            return res.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private void serveFile(OutputStream out, String relPath) throws IOException {
        File f = resolveSafe(relPath);
        if (f == null || !f.exists() || !f.isFile()) {
            write(out, 404, "Not Found", "text/plain", "Not found".getBytes("UTF-8"));
            return;
        }
        String name = f.getName().toLowerCase();
        String ct = "application/octet-stream";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) ct = "image/jpeg";
        else if (name.endsWith(".png")) ct = "image/png";
        else if (name.endsWith(".gif")) ct = "image/gif";
        else if (name.endsWith(".webp")) ct = "image/webp";
        else if (name.endsWith(".bmp")) ct = "image/bmp";
        long len = f.length();
        String head = "HTTP/1.1 200 OK\r\nContent-Type: " + ct + "\r\nContent-Length: " + len
                + "\r\nCache-Control: max-age=300\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        FileInputStream fis = new FileInputStream(f);
        byte[] buf = new byte[16384];
        int n;
        while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
        fis.close();
        out.flush();
    }

    private byte[] handleMkdir(String body) {
        try {
            JSONObject j = new JSONObject(body == null ? "{}" : body);
            String parent = j.optString("path", "");
            String name = j.optString("name", "").trim();
            if (name.length() == 0 || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
                return "{\"ok\":false,\"error\":\"invalid name\"}".getBytes("UTF-8");
            }
            File p = resolveSafe(parent);
            if (p == null) return "{\"ok\":false,\"error\":\"bad path\"}".getBytes("UTF-8");
            File nd = new File(p, name);
            if (nd.exists()) return "{\"ok\":false,\"error\":\"already exists\"}".getBytes("UTF-8");
            boolean ok = nd.mkdirs();
            return ("{\"ok\":" + ok + ",\"path\":\"" + jsonEsc(relativeToUploadDir(nd)) + "\"}").getBytes("UTF-8");
        } catch (Exception e) { return "{\"ok\":false}".getBytes(); }
    }

    private byte[] handleRename(String body) {
        try {
            JSONObject j = new JSONObject(body == null ? "{}" : body);
            String path = j.optString("path", "");
            String newName = j.optString("newName", "").trim();
            if (newName.length() == 0 || newName.contains("/") || newName.contains("\\")
                    || newName.equals(".") || newName.equals("..")) {
                return "{\"ok\":false,\"error\":\"invalid name\"}".getBytes("UTF-8");
            }
            File f = resolveSafe(path);
            if (f == null || !f.exists()) return "{\"ok\":false,\"error\":\"not found\"}".getBytes("UTF-8");
            File parent = f.getParentFile();
            File dest = new File(parent, newName);
            if (dest.exists()) return "{\"ok\":false,\"error\":\"name taken\"}".getBytes("UTF-8");
            boolean ok = f.renameTo(dest);
            return ("{\"ok\":" + ok + ",\"path\":\"" + jsonEsc(relativeToUploadDir(dest)) + "\"}").getBytes("UTF-8");
        } catch (Exception e) { return "{\"ok\":false}".getBytes(); }
    }

    private byte[] handleDelete(String body) {
        int deleted = 0, failed = 0;
        try {
            JSONObject j = new JSONObject(body == null ? "{}" : body);
            JSONArray arr = j.optJSONArray("paths");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    File f = resolveSafe(arr.optString(i, ""));
                    if (f == null || !f.exists() || f.getCanonicalPath().equals(uploadDir.getCanonicalPath())) {
                        failed++; continue;
                    }
                    if (deleteRecursive(f)) deleted++; else failed++;
                }
            }
        } catch (Exception e) { }
        return ("{\"deleted\":" + deleted + ",\"failed\":" + failed + "}").getBytes();
    }

    private boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) if (!deleteRecursive(k)) return false;
        }
        return f.delete();
    }

    private String readBody(InputStream in, Map<String, String> headers) throws IOException {
        String cl = headers.get("content-length");
        if (cl == null) return "";
        int n;
        try { n = Integer.parseInt(cl); } catch (NumberFormatException e) { return ""; }
        if (n <= 0 || n > 1024 * 1024) return "";
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r < 0) break;
            read += r;
        }
        return new String(buf, 0, read, "UTF-8");
    }

    private static String jsonEsc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    private static void write(OutputStream out, int code, String status, String contentType, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + " " + status + "\r\n" +
                      "Content-Type: " + contentType + "\r\n" +
                      "Content-Length: " + body.length + "\r\n" +
                      "Connection: close\r\n" +
                      "Cache-Control: no-store\r\n" +
                      "\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }
}
