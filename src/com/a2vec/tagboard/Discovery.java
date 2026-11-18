package com.a2vec.tagboard;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.provider.Settings;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * 局域网发现（与上位机 tag/discovery.cpp 同一协议）。
 *
 * 设计要点：
 *  · 独立低优先级线程，只收发 64 字节小包，不碰画板/HTTP/SSE 线程；
 *  · 默认被动：每 3 秒向组播 239.255.0.1:8888 发一次 ANNOUNCE（同时也发受限广播兜底）；
 *  · 收到上位机的 PROBE 后随机延迟 0~50ms 再单播回复，避免多台同时回包；
 *  · 报文带魔数 0x524F424F + CRC16，非法杂散包直接丢弃。
 */
public final class Discovery implements Runnable {
    public static final int    PORT  = 8888;
    public static final String GROUP = "239.255.0.1";

    private static final int MAGIC = 0x524F424F;   // "ROBO"
    private static final int T_ANNOUNCE = 1, T_PROBE = 2, T_RESPONSE = 3;
    private static final int PKT = 64;

    private final Context ctx;
    private final int     tcpPort, imgW, imgH;
    private final Random  rnd = new Random();
    private volatile boolean running = true;
    private MulticastSocket  sock;
    private WifiManager.MulticastLock mcLock;
    private Thread thread;
    private String sn   = "";
    private String name = "A2VEC TagBoard";

    public Discovery(Context c, int tcpPort, int w, int h) {
        this.ctx     = c.getApplicationContext();
        this.tcpPort = tcpPort;
        this.imgW    = w;
        this.imgH    = h;
    }

    public void start() {
        try {
            sn = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
        }
        if (sn == null || sn.isEmpty()) sn = "android";
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mcLock = wm.createMulticastLock("a2vec-tagboard");
                mcLock.setReferenceCounted(false);
                mcLock.acquire();
            }
        } catch (Throwable ignored) {
        }
        try {
            sock = new MulticastSocket(PORT);
            sock.setReuseAddress(true);
            sock.setSoTimeout(400);
            sock.joinGroup(InetAddress.getByName(GROUP));
            sock.setTimeToLive(1);
            sock.setLoopbackMode(true);
        } catch (Throwable t) {
            sock = null;
        }
        thread = new Thread(this, "tagboard-discovery");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    public void stop() {
        running = false;
        try {
            if (sock != null) sock.close();
        } catch (Throwable ignored) {
        }
        try {
            if (thread != null) thread.join(300);
        } catch (Throwable ignored) {
        }
        try {
            if (mcLock != null && mcLock.isHeld()) mcLock.release();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void run() {
        long lastAnnounce = 0;
        while (running) {
            final long now = System.currentTimeMillis();
            if (sock != null && now - lastAnnounce >= 3000) {
                announce();
                lastAnnounce = now;
            }
            if (sock == null) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            DatagramPacket p = new DatagramPacket(new byte[256], 256);
            try {
                sock.receive(p);
            } catch (java.net.SocketTimeoutException e) {
                continue;
            } catch (Throwable t) {
                if (!running) return;
                continue;
            }
            final byte[] d = p.getData();
            final int    n = p.getLength();
            if (n < PKT || readInt(d, 0) != MAGIC || d[5] != T_PROBE || !crcOk(d)) continue;
            // 随机 0~50ms 再回，避免多台设备同时应答
            try {
                Thread.sleep(rnd.nextInt(50));
            } catch (InterruptedException ignored) {
            }
            try {
                byte[] rsp = packet(T_RESPONSE);
                sock.send(new DatagramPacket(rsp, rsp.length, p.getAddress(), p.getPort()));
            } catch (Throwable ignored) {
            }
        }
    }

    private void announce() {
        try {
            byte[] b = packet(T_ANNOUNCE);
            sock.send(new DatagramPacket(b, b.length, InetAddress.getByName(GROUP), PORT));
            sock.send(new DatagramPacket(b, b.length, InetAddress.getByName("255.255.255.255"), PORT));
        } catch (Throwable ignored) {
        }
    }

    private byte[] packet(int type) {
        byte[] b = new byte[PKT];
        putInt(b, 0, MAGIC);
        b[4] = 1;
        b[5] = (byte) type;
        putShort(b, 6, tcpPort);
        putShort(b, 8, imgW);
        putShort(b, 10, imgH);
        byte[] s = sn.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(s, 0, b, 12, Math.min(15, s.length));
        byte[] nm = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nm, 0, b, 28, Math.min(31, nm.length));
        int c = crc16(b, 60);
        b[60] = (byte) (c >> 8);
        b[61] = (byte) (c & 0xFF);
        return b;
    }

    private static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 24);
        b[o + 1] = (byte) (v >> 16);
        b[o + 2] = (byte) (v >> 8);
        b[o + 3] = (byte) v;
    }

    private static int readInt(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static void putShort(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 8);
        b[o + 1] = (byte) v;
    }

    private static boolean crcOk(byte[] b) {
        int c = ((b[60] & 0xFF) << 8) | (b[61] & 0xFF);
        return c == crc16(b, 60);
    }

    private static int crc16(byte[] d, int n) {
        int crc = 0xFFFF;
        for (int i = 0; i < n; ++i) {
            crc ^= (d[i] & 0xFF) << 8;
            for (int k = 0; k < 8; ++k)
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
        }
        return crc & 0xFFFF;
    }
}
