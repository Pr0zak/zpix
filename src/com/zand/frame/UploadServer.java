package com.zand.frame;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny HTTP server that lets any device on the wifi drop photos into a folder
 * on the tablet. Serves an HTML upload page at /, accepts multipart/form-data
 * POSTs at /upload.
 */
public class UploadServer {
    private final int port;
    private final File uploadDir;
    private final byte[] htmlBytes;
    private final Runnable onUpload;
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;

    public UploadServer(int port, File uploadDir, byte[] htmlBytes, Runnable onUpload) {
        this.port = port;
        this.uploadDir = uploadDir;
        this.htmlBytes = htmlBytes;
        this.onUpload = onUpload;
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
            } else if ("POST".equals(method) && path.equals("/upload")) {
                int saved = handleUpload(in, headers);
                if (saved > 0 && onUpload != null) onUpload.run();
                byte[] body = ("{\"saved\":" + saved + "}").getBytes("UTF-8");
                write(out, 200, "OK", "application/json", body);
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
                outFile = new File(uploadDir, uniqueName(sanitize(filename)));
                fout = new BufferedOutputStream(new FileOutputStream(outFile), 16384);
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

    private static String sanitize(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean ok = ch == '.' || ch == '-' || ch == '_' || ch == ' '
                    || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9');
            sb.append(ok ? ch : '_');
        }
        String safe = sb.toString().trim();
        return safe.length() > 0 ? safe : "photo.jpg";
    }

    private String uniqueName(String name) {
        File f = new File(uploadDir, name);
        if (!f.exists()) return name;
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String ext = dot >= 0 ? name.substring(dot) : "";
        for (int i = 1; i < 100000; i++) {
            String alt = base + "_" + i + ext;
            if (!new File(uploadDir, alt).exists()) return alt;
        }
        return name;
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
