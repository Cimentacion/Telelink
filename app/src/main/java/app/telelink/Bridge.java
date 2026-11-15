package app.telelink;

import android.webkit.CookieManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Puente: servidor HTTP en el móvil. La tele le pide el vídeo al móvil y el móvil se lo pide a la
 * web con las mismas cabeceras y cookies que el navegador, así la web no rechaza a la tele.
 *
 *   /p/<id>/...   proxy: archivos (con Range) y listas HLS reescritas para que todo pase por aquí
 *   /ts/<id>/...  modo compatible: une los trozos HLS en un único flujo MPEG-TS continuo
 */
final class Bridge {

    static final class Item {
        String url;
        Map<String, String> headers;
    }

    static final class Probe {
        int code;
        boolean hls;
        String type;
    }

    private static final Map<String, Item> ITEMS = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> KEYS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger(1);
    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static ServerSocket server;

    static final AtomicInteger hits = new AtomicInteger();
    static volatile String lastError = "";

    private Bridge() {}

    // ---------- Arranque ----------

    static synchronized int start() throws IOException {
        if (server == null || server.isClosed()) {
            server = new ServerSocket(0);
            final ServerSocket s = server;
            Thread t = new Thread(() -> {
                while (!s.isClosed()) {
                    try {
                        final Socket c = s.accept();
                        POOL.execute(() -> handle(c));
                    } catch (IOException e) {
                        break;
                    }
                }
            }, "telelink-bridge");
            t.setDaemon(true);
            t.start();
        }
        return server.getLocalPort();
    }

    static synchronized void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
        ITEMS.clear();
        KEYS.clear();
    }

    static String register(String url, Map<String, String> headers) {
        Item it = new Item();
        it.url = url;
        it.headers = headers;
        String id = Integer.toString(NEXT.getAndIncrement(), 36);
        ITEMS.put(id, it);
        hits.set(0);
        lastError = "";
        return id;
    }

    private static String child(Item parent, String url) {
        Item it = new Item();
        it.url = url;
        it.headers = parent.headers;
        String id = Integer.toString(NEXT.getAndIncrement(), 36);
        ITEMS.put(id, it);
        return id;
    }

    // ---------- Peticiones a la web ----------

    private static HttpURLConnection open(Item it, String url, String range) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(true);
        if (it.headers != null) {
            for (Map.Entry<String, String> h : it.headers.entrySet()) {
                String k = h.getKey();
                if (k == null || h.getValue() == null) continue;
                String l = k.toLowerCase(Locale.US);
                if (l.equals("range") || l.equals("accept-encoding") || l.equals("host")
                        || l.equals("cookie") || l.equals("connection") || l.equals("content-length")) continue;
                c.setRequestProperty(k, h.getValue());
            }
        }
        c.setRequestProperty("Accept-Encoding", "identity");
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        } catch (Exception ignored) {
        }
        if (range != null) c.setRequestProperty("Range", range);
        return c;
    }

    /** Comprueba desde el móvil que la web entrega el vídeo y de qué tipo es. */
    static Probe probe(String id) throws IOException {
        Item it = ITEMS.get(id);
        if (it == null) throw new IOException("vídeo no registrado");
        HttpURLConnection c = open(it, it.url, "bytes=0-2047");
        Probe p = new Probe();
        try {
            p.code = c.getResponseCode();
            p.type = c.getContentType();
            String path = c.getURL().getPath() == null ? "" : c.getURL().getPath().toLowerCase(Locale.US);
            boolean byName = path.endsWith(".m3u8")
                    || (p.type != null && p.type.toLowerCase(Locale.US).contains("mpegurl"));
            boolean byBody = false;
            if (p.code < 400) {
                byte[] head = new byte[7];
                InputStream in = c.getInputStream();
                int n = 0;
                while (n < 7) {
                    int r = in.read(head, n, 7 - n);
                    if (r < 0) break;
                    n += r;
                }
                byBody = n == 7 && new String(head, StandardCharsets.US_ASCII).equals("#EXTM3U");
            }
            p.hls = byName || byBody;
        } finally {
            c.disconnect();
        }
        return p;
    }

    private static String fetchText(Item it, String url, String[] finalUrl) throws IOException {
        HttpURLConnection c = open(it, url, null);
        int code = c.getResponseCode();
        if (code >= 400) {
            c.disconnect();
            throw new IOException("HTTP " + code + " en " + new URL(url).getHost());
        }
        String text = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
        if (finalUrl != null) finalUrl[0] = c.getURL().toString();
        return text;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        in.close();
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }

    // ---------- Servidor ----------

    private static void handle(Socket sock) {
        try (Socket s = sock) {
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            ByteArrayOutputStream hb = new ByteArrayOutputStream();
            int state = 0, b;
            while (hb.size() < 16384 && (b = in.read()) >= 0) {
                hb.write(b);
                if (b == '\r' && (state == 0 || state == 2)) state++;
                else if (b == '\n' && (state == 1 || state == 3)) state++;
                else state = 0;
                if (state == 4) break;
            }
            String[] lines = new String(hb.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
            if (lines.length == 0) return;
            String[] rl = lines[0].split(" ");
            if (rl.length < 2) return;
            boolean headOnly = rl[0].equalsIgnoreCase("HEAD");
            String path = rl[1];
            Map<String, String> rh = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) rh.put(lines[i].substring(0, c).trim().toLowerCase(Locale.US), lines[i].substring(c + 1).trim());
            }
            hits.incrementAndGet();

            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
            String[] parts = path.split("/");
            // parts: "", "p"|"ts", id, nombre
            if (parts.length < 3) {
                simple(out, 404, "Not Found");
                return;
            }
            Item it = ITEMS.get(parts[2]);
            if (it == null) {
                simple(out, 404, "Not Found");
                return;
            }
            String host = rh.get("host");
            String base = "http://" + (host != null ? host : s.getLocalAddress().getHostAddress() + ":" + s.getLocalPort());
            try {
                if (parts[1].equals("ts")) serveTs(it, out, headOnly);
                else serveProxy(it, base, rh.get("range"), out, headOnly);
            } catch (IOException e) {
                // Si la tele corta la conexión (cambia de posición, para...) no es un error real.
                String m = String.valueOf(e.getMessage());
                if (!m.contains("Broken pipe") && !m.contains("Connection reset") && !m.contains("Socket closed")) {
                    lastError = m;
                }
            }
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private static void simple(OutputStream out, int code, String reason) throws IOException {
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void serveProxy(Item it, String base, String range, OutputStream out, boolean headOnly)
            throws IOException {
        HttpURLConnection up = open(it, it.url, range);
        try {
            int code = up.getResponseCode();
            if (code >= 400) {
                lastError = "La web respondió HTTP " + code + " (" + up.getURL().getHost() + ")";
                simple(out, code == 404 ? 404 : 403, "Upstream");
                return;
            }
            String type = up.getContentType();
            String path = up.getURL().getPath() == null ? "" : up.getURL().getPath().toLowerCase(Locale.US);
            BufferedInputStream body = new BufferedInputStream(up.getInputStream(), 65536);
            body.mark(16);
            byte[] head = new byte[7];
            int n = 0;
            while (n < 7) {
                int r = body.read(head, n, 7 - n);
                if (r < 0) break;
                n += r;
            }
            body.reset();
            boolean playlist = (n == 7 && new String(head, StandardCharsets.US_ASCII).equals("#EXTM3U"))
                    || path.endsWith(".m3u8");

            StringBuilder h = new StringBuilder();
            if (playlist) {
                String text = new String(readAll(body), StandardCharsets.UTF_8);
                byte[] data = rewrite(it, text, up.getURL().toString(), base).getBytes(StandardCharsets.UTF_8);
                h.append("HTTP/1.1 200 OK\r\n")
                        .append("Content-Type: application/vnd.apple.mpegurl\r\n")
                        .append("Content-Length: ").append(data.length).append("\r\n")
                        .append("Cache-Control: no-cache\r\n")
                        .append("Connection: close\r\n\r\n");
                out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
                if (!headOnly) out.write(data);
                return;
            }

            if (type == null || type.startsWith("text/") || type.contains("octet-stream")
                    || type.startsWith("image/")) {
                // Muchos hosts disfrazan los trozos de vídeo como imágenes o binarios.
                type = path.endsWith(".mp4") ? "video/mp4" : path.endsWith(".key") ? "application/octet-stream" : "video/mp2t";
            }
            h.append("HTTP/1.1 ").append(code == 206 ? "206 Partial Content" : "200 OK").append("\r\n")
                    .append("Content-Type: ").append(type).append("\r\n");
            long len = up.getContentLengthLong();
            if (len >= 0) h.append("Content-Length: ").append(len).append("\r\n");
            String cr = up.getHeaderField("Content-Range");
            if (cr != null) h.append("Content-Range: ").append(cr).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n")
                    .append("transferMode.dlna.org: Streaming\r\n")
                    .append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
            if (!headOnly) copy(body, out);
        } finally {
            up.disconnect();
        }
    }

    private static String resolve(String base, String ref) throws IOException {
        return new URL(new URL(base), ref.trim()).toString();
    }

    private static String childUrl(Item parent, String base, String abs) {
        String p = abs.toLowerCase(Locale.US);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        String name = p.endsWith(".m3u8") ? "index.m3u8" : p.endsWith(".key") ? "k.key"
                : p.endsWith(".mp4") || p.endsWith(".m4s") ? "seg.mp4" : "seg.ts";
        return base + "/p/" + child(parent, abs) + "/" + name;
    }

    /** Reescribe una lista HLS para que todas sus direcciones pasen por el puente. */
    private static String rewrite(Item it, String text, String selfUrl, String base) throws IOException {
        StringBuilder sb = new StringBuilder();
        Pattern uri = Pattern.compile("URI=\"([^\"]+)\"");
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                sb.append('\n');
            } else if (line.startsWith("#")) {
                Matcher m = uri.matcher(line);
                StringBuffer out = new StringBuffer();
                while (m.find()) {
                    String rep = "URI=\"" + childUrl(it, base, resolve(selfUrl, m.group(1))) + "\"";
                    m.appendReplacement(out, Matcher.quoteReplacement(rep));
                }
                m.appendTail(out);
                sb.append(out).append('\n');
            } else {
                sb.append(childUrl(it, base, resolve(selfUrl, line))).append('\n');
            }
        }
        return sb.toString();
    }

    // ---------- Modo compatible: HLS -> un único MPEG-TS ----------

    private static final class Seg {
        String url;
        String keyUrl;
        byte[] iv;
    }

    private static String attr(String line, String name) {
        Matcher m = Pattern.compile(name + "=(\"([^\"]*)\"|[^,]*)").matcher(line);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(1);
    }

    private static List<Seg> segments(Item it) throws IOException {
        String[] fin = new String[1];
        String text = fetchText(it, it.url, fin);
        String self = fin[0];

        if (text.contains("#EXT-X-STREAM-INF")) {
            long best = -1;
            String bestUrl = null;
            String[] ls = text.split("\n");
            for (int i = 0; i < ls.length; i++) {
                String l = ls[i].trim();
                if (!l.startsWith("#EXT-X-STREAM-INF")) continue;
                long bw = 0;
                try {
                    bw = Long.parseLong(attr(l, "BANDWIDTH").trim());
                } catch (Exception ignored) {
                }
                for (int j = i + 1; j < ls.length; j++) {
                    String n = ls[j].trim();
                    if (n.isEmpty() || n.startsWith("#")) continue;
                    if (bw > best) {
                        best = bw;
                        bestUrl = resolve(self, n);
                    }
                    break;
                }
            }
            if (bestUrl == null) throw new IOException("lista HLS sin calidades");
            text = fetchText(it, bestUrl, fin);
            self = fin[0];
        }

        List<Seg> list = new ArrayList<>();
        long seq = 0;
        String keyUrl = null;
        byte[] iv = null;
        for (String raw : text.split("\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try {
                    seq = Long.parseLong(l.substring(l.indexOf(':') + 1).trim());
                } catch (Exception ignored) {
                }
            } else if (l.startsWith("#EXT-X-MAP")) {
                throw new IOException("este vídeo usa trozos fMP4; el modo compatible solo admite MPEG-TS");
            } else if (l.startsWith("#EXT-X-KEY")) {
                String method = attr(l, "METHOD");
                if (method == null || method.equals("NONE")) {
                    keyUrl = null;
                    iv = null;
                } else if (method.equals("AES-128")) {
                    keyUrl = resolve(self, attr(l, "URI"));
                    String hex = attr(l, "IV");
                    iv = hex == null ? null : hexIv(hex);
                } else {
                    throw new IOException("cifrado no soportado: " + method);
                }
            } else if (!l.startsWith("#")) {
                Seg s = new Seg();
                s.url = resolve(self, l);
                s.keyUrl = keyUrl;
                if (keyUrl != null) s.iv = iv != null ? iv : seqIv(seq);
                list.add(s);
                seq++;
            }
        }
        if (list.isEmpty()) throw new IOException("lista HLS vacía");
        return list;
    }

    private static byte[] hexIv(String hex) {
        String h = hex.trim();
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
        while (h.length() < 32) h = "0" + h;
        byte[] iv = new byte[16];
        for (int i = 0; i < 16; i++) iv[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return iv;
    }

    private static byte[] seqIv(long seq) {
        byte[] iv = new byte[16];
        for (int i = 0; i < 8; i++) iv[15 - i] = (byte) (seq >>> (8 * i));
        return iv;
    }

    private static void serveTs(Item it, OutputStream out, boolean headOnly) throws IOException {
        List<Seg> segs;
        try {
            segs = segments(it);
        } catch (IOException e) {
            lastError = String.valueOf(e.getMessage());
            simple(out, 404, "Not Found");
            return;
        }
        String h = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: video/mpeg\r\n"
                + "transferMode.dlna.org: Streaming\r\n"
                + "contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        if (headOnly) return;

        for (Seg s : segs) {
            HttpURLConnection c = open(it, s.url, null);
            try {
                int code = c.getResponseCode();
                if (code >= 400) throw new IOException("La web respondió HTTP " + code + " en un trozo del vídeo");
                InputStream in = c.getInputStream();
                if (s.keyUrl != null) {
                    byte[] key = KEYS.get(s.keyUrl);
                    if (key == null) {
                        HttpURLConnection kc = open(it, s.keyUrl, null);
                        key = readAll(kc.getInputStream());
                        KEYS.put(s.keyUrl, key);
                    }
                    try {
                        Cipher ci = Cipher.getInstance("AES/CBC/PKCS5Padding");
                        ci.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(s.iv));
                        in = new CipherInputStream(in, ci);
                    } catch (Exception e) {
                        throw new IOException("no se pudo descifrar el vídeo");
                    }
                }
                copy(in, out);
                out.flush();
            } finally {
                c.disconnect();
            }
        }
    }
}
