package com.vishworks.assetmgmt.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vishworks.assetmgmt.json.JsonCodec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Response helpers and server bootstrap: JSON, plain-text error, 404 and redirect responses,
 * plus a listener that serves each request on its own virtual thread.
 */
public final class Http {
  private Http() {}

  /** A bare ":8090" listens on all interfaces. Requests are served on virtual threads. */
  public static HttpServer listen(String addr, com.sun.net.httpserver.HttpHandler handler) throws IOException {
    int colon = addr.lastIndexOf(':');
    String host = colon > 0 ? addr.substring(0, colon) : "";
    int port = Integer.parseInt(addr.substring(colon + 1));
    InetSocketAddress sa = host.isEmpty() ? new InetSocketAddress(port) : new InetSocketAddress(host, port);
    HttpServer s = HttpServer.create(sa, 0);
    s.createContext("/", handler);
    s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    s.start();
    return s;
  }

  /** Writes a complete response. A HEAD request gets the headers only. */
  public static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
    if (contentType != null) {
      ex.getResponseHeaders().set("Content-Type", contentType);
    }
    boolean head = "HEAD".equals(ex.getRequestMethod());
    if (head || body.length == 0 || status == 304 || status == 204) {
      ex.sendResponseHeaders(status, -1);
    } else {
      ex.sendResponseHeaders(status, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    }
    ex.close();
  }

  /** writeJSON from cmd/apiserver: Content-Type application/json + json.Encoder output. */
  public static void writeJSON(HttpExchange ex, int code, Object v) throws IOException {
    send(ex, code, "application/json", JsonCodec.encode(v));
  }

  /** http.Error. */
  public static void error(HttpExchange ex, String msg, int code) throws IOException {
    ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    send(ex, code, "text/plain; charset=utf-8", (msg + "\n").getBytes(StandardCharsets.UTF_8));
  }

  /** http.NotFound. */
  public static void notFound(HttpExchange ex) throws IOException {
    error(ex, "404 page not found", 404);
  }

  /** http.Redirect for GET/HEAD requests. */
  public static void redirect(HttpExchange ex, String url, int code) throws IOException {
    ex.getResponseHeaders().set("Location", url);
    String method = ex.getRequestMethod();
    if ("GET".equals(method) || "HEAD".equals(method)) {
      String body = "<a href=\"" + htmlEscape(url) + "\">" + statusText(code) + "</a>.\n\n";
      send(ex, code, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    } else {
      send(ex, code, null, new byte[0]);
    }
  }

  static String statusText(int code) {
    return switch (code) {
      case 301 -> "Moved Permanently";
      case 302 -> "Found";
      case 304 -> "Not Modified";
      case 307 -> "Temporary Redirect";
      case 308 -> "Permanent Redirect";
      default -> "";
    };
  }

  /** Escapes the five characters that matter when interpolating text into HTML. */
  public static String htmlEscape(String s) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&' -> b.append("&amp;");
        case '<' -> b.append("&lt;");
        case '>' -> b.append("&gt;");
        case '"' -> b.append("&#34;");
        case '\'' -> b.append("&#39;");
        default -> b.append(c);
      }
    }
    return b.toString();
  }

  /** Reads the whole request body. */
  public static byte[] body(HttpExchange ex) throws IOException {
    return ex.getRequestBody().readAllBytes();
  }
}
