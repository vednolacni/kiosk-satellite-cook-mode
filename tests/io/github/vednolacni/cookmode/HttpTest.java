// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/** Http against a local server: headers, bodies, limits and redirects that must not carry the token away. */
public final class HttpTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            send(exchange, "Bearer secret".equals(auth) ? 200 : 401, "{\"ok\":true}");
        });
        server.createContext("/echo", exchange -> {
            byte[] body = readAll(exchange.getRequestBody());
            send(exchange, 201, exchange.getRequestMethod() + " " + new String(body, StandardCharsets.UTF_8));
        });
        server.createContext("/big", exchange -> send(exchange, 200, new String(new char[5000]).replace('\0', 'x')));
        server.createContext("/same", exchange -> redirect(exchange, "/ok"));
        server.createContext("/away", exchange -> redirect(exchange, "http://example.invalid/steal"));
        server.createContext("/loop", exchange -> redirect(exchange, "/loop"));
        server.createContext("/missing", exchange -> send(exchange, 404, "{\"detail\":\"Not found.\"}"));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Map<String, String> auth = Collections.singletonMap("Authorization", "Bearer secret");
        Http http = new Http();
        try {
            Http.Response ok = http.request("GET", base + "/ok", auth, null, 1024);
            assert ok.ok() && "{\"ok\":true}".equals(ok.text());
            assert http.request("GET", base + "/ok", Collections.<String, String>emptyMap(), null, 1024).status == 401;
            Http.Response echo = http.request("POST", base + "/echo", auth, "{\"a\":1}".getBytes(StandardCharsets.UTF_8), 1024);
            assert echo.status == 201 && "POST {\"a\":1}".equals(echo.text());
            Http.Response missing = http.request("GET", base + "/missing", auth, null, 1024);
            assert missing.status == 404 && missing.text().contains("Not found");
            // A redirect within the server keeps the token. One to another host is refused before anything is sent.
            assert http.request("GET", base + "/same", auth, null, 1024).ok();
            assert fails(http, base + "/away", "another host");
            assert fails(http, base + "/loop", "Too many redirects");
            assert fails(http, base + "/big", "larger than");
        } finally {
            server.stop(0);
        }
        System.out.println("HttpTest passed");
    }

    static boolean fails(Http http, String url, String message) {
        try {
            http.request("GET", url, Collections.singletonMap("Authorization", "Bearer secret"), null, 1024);
            return false;
        } catch (IOException expected) {
            return expected.getMessage().contains(message);
        }
    }

    static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }
}
