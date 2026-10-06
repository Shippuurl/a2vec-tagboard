package com.a2vec.tagboard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Enumeration;

/**
 * 标定参数服务：手机自己在局域网里开一个 HTTP 端口，开发端（上位机 python / 浏览器）
 * 直接拉取本机屏幕的 DPI 和标定板几何参数，不用手抄。
 *
 * 端点：
 *   GET  /                 简单文本说明
 *   GET  /tagboard.json    全部参数（就是需要的那个）
 *   GET  /tagboard/params  python 直接用的最小参数集
 *   GET  /health           {"ok":true}
 *   POST /tagboard/pose    开发端把算出来的相机位姿回传，手机上显示，便于现场核对
 *   OPTIONS *               CORS 预检
 *
 * 只监听 0.0.0.0，无认证，仅用于同一局域网的标定现场。
 */
public class CalibServer implements Runnable {

    public static final int DEFAULT_PORT = 8899;

    private final int port;
    private final TagBoardView view;
    private ServerSocket server;
    private volatile boolean running;
    private Thread thread;
    private volatile String status = "未启动";
    private volatile long poseStamp = 0L;
    private volatile String poseText = "";

    public CalibServer(int port, TagBoardView view) {
        this.port = port;
        this.view = view;
    }

    public String getStatus() {
        return status;
    }

    public int getPort() {
        return port;
    }

    public String getPoseText() {
        return poseText;
    }

    public long getPoseStamp() {
        return poseStamp;
    }

    public static final String TAG = "TagBoardCalib";

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "calib-server");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void run() {
        try {
            loop();
        } catch (Throwable t) {
            android.util.Log.e(TAG, "server 线程异常退出", t);
            status = "异常退出: " + t;
            view.postInvalidate();
        }
    }

    private void loop() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress("0.0.0.0", port), 16);
        } catch (Throwable e) {
            status = "启动失败: " + e;
            android.util.Log.e(TAG, "bind 失败 port=" + port, e);
            view.postInvalidate();
            return;
        }
        status = "监听 " + localIpv4() + ":" + port;

        while (running) {
            try {
                Socket s = server.accept();
                handle(s);
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) {
                if (running) status = "连接错误: " + e.getMessage();
                view.postInvalidate();
            }
        }
        status = "已停止";
        view.postInvalidate();
    }

    private <T> T onUi(java.util.concurrent.Callable<T> operation) throws Exception {
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(operation);
        if (!view.post(task)) throw new IOException("UI unavailable");
        try { return task.get(2, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception error) { task.cancel(false); throw error; }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(4000);
            InputStream in = s.getInputStream();
            String header = readHeader(in);
            if (header == null) return;

            String[] first = header.split("\r\n")[0].split(" ");
            String method = first.length > 0 ? first[0] : "GET";
            String path = first.length > 1 ? first[1] : "/";
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }

            String body;
            String type = "application/json; charset=utf-8";
            if (path.equals("/") || path.equals("/index.html")) {
                type = "text/plain; charset=utf-8";
                body = "a2vec TagBoard 标定端口\n"
                        + "  GET  /tagboard.json    全部参数\n"
                        + "  GET  /tagboard/params  python 最小参数集\n"
                        + "  GET  /tagboard/board.png  设备上真正渲染出的板（用于比对）\n"
                        + "  GET  /health\n"
                        + "  POST /tagboard/pose    回传相机位姿\n";
            } else if (path.equals("/tagboard.json")) {
                body = onUi(() -> view.toJson(false));
            } else if (path.equals("/tagboard/params")) {
                body = onUi(() -> view.toJson(true));
            } else if (path.equals("/tagboard/board.png")) {
                // 设备上真正渲染出来的板，导出给开发端和参考图逐像素比对
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(1 << 20);
                onUi(() -> view.boardBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bo));
                respondBytes(s, 200, "image/png", bo.toByteArray());
                return;
            } else if (path.equals("/tagboard/layout")) {
                if (!"POST".equals(method)) {
                    respond(s, 405, "application/json", "{\"ok\":false,\"error\":\"POST required\"}");
                    return;
                }
                String requested = query.startsWith("layout=") ? query.substring(7) : "";
                if (!"compact-6".equals(requested) && !"classic-12".equals(requested)) {
                    respond(s, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"unknown layout\"}");
                    return;
                }
                body = onUi(() -> { view.setLayout(requested); return view.toJson(false); });
            } else if (path.equals("/health")) {
                body = "{\"ok\":true,\"service\":\"a2vec-tagboard\",\"port\":" + port + "}";
            } else if (path.equals("/tagboard/pose")) {
                String payload = readBody(in, header);
                poseText = payload;
                poseStamp = System.currentTimeMillis();
                view.postInvalidate();
                body = "{\"ok\":true}";
            } else {
                respond(s, 404, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"unknown path\"}");
                return;
            }
            respond(s, 200, type, body);
        } catch (Exception e) {
            try {
                respond(s, 500, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e.getMessage())) + "\"}");
            } catch (IOException ignored) {
            }
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readHeader(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            sb.append((char) c);
            int n = sb.length();
            if (n >= 4 && sb.charAt(n - 4) == '\r' && sb.charAt(n - 3) == '\n'
                    && sb.charAt(n - 2) == '\r' && sb.charAt(n - 1) == '\n') break;
            if (n > 16384) break;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String readBody(InputStream in, String header) throws IOException {
        int len = 0;
        for (String line : header.split("\r\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    len = Integer.parseInt(line.substring(i + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (len <= 0) return "";
        if (len > 65536) len = 65536;
        byte[] buf = new byte[len];
        int got = 0;
        while (got < len) {
            int r = in.read(buf, got, len - got);
            if (r < 0) break;
            got += r;
        }
        return new String(buf, 0, got, Charset.forName("UTF-8"));
    }

    private static void respondBytes(Socket s, int code, String type, byte[] b) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ')
                .append(code == 200 ? "OK" : "Error").append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(b.length).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Connection: close\r\n\r\n");
        OutputStream out = s.getOutputStream();
        out.write(h.toString().getBytes(Charset.forName("ISO-8859-1")));
        out.write(b);
        out.flush();
    }

    private static void respond(Socket s, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(Charset.forName("UTF-8"));
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ')
                .append(code == 200 ? "OK" : (code == 404 ? "Not Found" : "Error")).append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(b.length).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        h.append("Access-Control-Allow-Headers: *\r\n");
        h.append("Connection: close\r\n\r\n");
        OutputStream out = s.getOutputStream();
        out.write(h.toString().getBytes(Charset.forName("ISO-8859-1")));
        out.write(b);
        out.flush();
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 取本机第一个 IPv4 地址，供手机屏幕上显示。 */
    public static String localIpv4() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifs)) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "0.0.0.0";
    }
}
