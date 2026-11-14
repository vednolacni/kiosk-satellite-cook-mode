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
        server.createContext("/withheld", exchange -> {
            // Says its length at once, and sends nothing for a while.
            exchange.sendResponseHeaders(200, 5000);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                Thread.sleep(3000);
                out.write(new byte[5000]);
            } catch (IOException | InterruptedException gone) {
                // The reader stopped.
            }
        });
        server.createContext("/flood", exchange -> {
            // No length said, so it shows only as it comes.
            exchange.sendResponseHeaders(200, 0);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(new byte[5000]);
            } catch (IOException gone) {
                // The reader stopped.
            }
        });
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
            assert fails(http, base + "/away", "http://example.invalid");
            assert follows("http://a.test/x", "http://a.test/y") && follows("http://a.test:8080/x", "http://A.TEST:8080/y") && follows("https://a.test/x", "https://a.test:443/y");
            // The same host over https is better. Anything else may be another server, or leave the token in plain text.
            assert follows("http://a.test/x", "https://a.test/y") && follows("http://a.test:8080/x", "https://a.test:8443/y");
            assert !follows("https://a.test/x", "http://a.test/y") && !follows("http://a.test:8080/x", "http://a.test:9090/y");
            assert !follows("http://a.test/x", "http://b.test/x") && !follows("http://a.test/x", "ftp://a.test/x");
            assert fails(http, base + "/loop", "Too many redirects");
            assert fails(http, base + "/big", "larger than") && fails(http, base + "/flood", "larger than");
            // Too large is a kind of its own, which is the same every time.
            for (String path : new String[] {"/big", "/flood"}) {
                try {
                    http.request("GET", base + path, auth, null, 1024);
                    assert false : path;
                } catch (Http.TooLarge expected) {
                    assert expected.getMessage().equals("The answer is larger than 1 KB") && expected.status == 200 : expected.getMessage();
                }
            }
            // A length said up front that is too large is refused before the body comes.
            long started = System.nanoTime();
            try {
                http.request("GET", base + "/withheld", auth, null, 1024);
                assert false : "/withheld";
            } catch (Http.TooLarge expected) {
                assert expected.status == 200;
            }
            assert System.nanoTime() - started < 2_000_000_000L : (System.nanoTime() - started) / 1_000_000 + " ms";
        } finally {
            server.stop(0);
        }
        System.out.println("HttpTest passed");
    }

    static boolean follows(String from, String to) throws IOException {
        return Http.mayFollow(new java.net.URL(from), new java.net.URL(to));
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
