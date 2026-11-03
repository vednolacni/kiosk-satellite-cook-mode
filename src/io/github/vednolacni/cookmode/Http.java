// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blocking HTTP for the plugin's own network thread, never the plugin's callback worker or the main thread. Redirects
 * are followed only to the same server, or from http to https on the same host, so the API token never travels to
 * another server or falls back to plain text. cancelAll closes every open connection, which the plugin's stop needs
 * because a blocked read ignores interruption.
 */
final class Http {
    /** The server sent the request somewhere it must not go. Nothing was sent there. */
    static final class Refused extends IOException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    static final class Response {
        final int status;
        final byte[] body;

        Response(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    static final int CONNECT_MS = 6_000;
    static final int READ_MS = 20_000;
    /** The read timeout is per read, so an answer that trickles needs a limit of its own. */
    static final long ANSWER_MS = 60_000L;
    private static final int MAX_REDIRECTS = 3;

    private final Set<HttpURLConnection> open = Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection, Boolean>());

    /** Send a request and read the answer's body, up to limit bytes. A larger body throws. */
    Response request(String method, String url, Map<String, String> headers, byte[] body, int limit) throws IOException {
        URL target = new URL(url);
        if (!web(target)) throw new Refused("Not a web address: " + target.getProtocol());
        for (int redirect = 0; ; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) target.openConnection();
            open.add(connection);
            try {
                connection.setRequestMethod(method);
                connection.setConnectTimeout(CONNECT_MS);
                connection.setReadTimeout(READ_MS);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(body);
                    }
                }
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400 && status != 304) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirect >= MAX_REDIRECTS) throw new IOException("Too many redirects from " + url);
                    URL next = new URL(target, location);
                    if (!mayFollow(target, next)) throw new Refused("The server sent the request on to " + next.getProtocol() + "://" + next.getAuthority());
                    target = next;
                    continue;
                }
                InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                return new Response(status, in == null ? new byte[0] : read(in, limit));
            } finally {
                open.remove(connection);
                connection.disconnect();
            }
        }
    }

    /** Whether a request and its headers may go on from one address to the other. */
    static boolean mayFollow(URL from, URL to) {
        if (!web(to) || !to.getHost().equalsIgnoreCase(from.getHost())) return false;
        if (to.getProtocol().equals(from.getProtocol())) return port(to) == port(from);
        return to.getProtocol().equals("https");
    }

    private static boolean web(URL url) {
        return url.getProtocol().equals("http") || url.getProtocol().equals("https");
    }

    private static int port(URL url) {
        return url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
    }

    /** Close every open connection. Their reads throw at once. */
    void cancelAll() {
        for (HttpURLConnection connection : open) connection.disconnect();
        open.clear();
    }

    private static byte[] read(InputStream in, int limit) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long started = System.nanoTime();
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (System.nanoTime() - started > ANSWER_MS * 1_000_000L) throw new IOException("The answer took longer than " + ANSWER_MS / 1000 + " seconds");
                total += count;
                if (total > limit) throw new IOException("The answer is larger than " + limit / 1024 + " KB");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
}
