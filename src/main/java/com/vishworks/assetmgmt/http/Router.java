package com.vishworks.assetmgmt.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Request router over {@code com.sun.net.httpserver}, which matches on path prefixes only.
 * This adds method matching and single-segment wildcards:
 *
 * <ul>
 *   <li>{@code "GET /api/x/{id}"} — method + path with single-segment wildcards (a GET
 *       pattern also serves HEAD);
 *   <li>{@code "/{$}"} — exactly "/";
 *   <li>{@code "GET /reports/"} — trailing slash: prefix match ("/reports" redirects to "/reports/");
 *   <li>a pattern without a method matches every method;
 *   <li>more specific patterns win; a path that matches only patterns for other methods
 *       gets 405 with an Allow header; anything else gets "404 page not found";
 *   <li>unclean paths ("//", "/./", "/../") are redirected to their clean form.
 * </ul>
 */
public final class Router implements HttpHandler {
  private static final Logger LOG = LoggerFactory.getLogger(Router.class);

  /** A request handler. */
  @FunctionalInterface
  public interface Handler {
    void handle(Request r) throws Exception;
  }

  /** The exchange plus the matched path wildcards. */
  public static final class Request {
    public final HttpExchange ex;
    private final Map<String, String> pathValues;
    /** Path below the matched prefix pattern (for "…/" patterns). */
    public final String rest;

    Request(HttpExchange ex, Map<String, String> pathValues, String rest) {
      this.ex = ex;
      this.pathValues = pathValues;
      this.rest = rest;
    }

    /** r.PathValue(name). */
    public String pathValue(String name) {
      return pathValues.getOrDefault(name, "");
    }

    public String method() {
      return ex.getRequestMethod();
    }

    public byte[] body() throws IOException {
      return Http.body(ex);
    }
  }

  private record Route(String method, List<String> segs, boolean prefix, boolean exactRoot, Handler h, int order) {}

  private final List<Route> routes = new ArrayList<>();

  /** mux.HandleFunc(pattern, handler). */
  public Router handle(String pattern, Handler h) {
    String method = null;
    String path = pattern;
    int sp = pattern.indexOf(' ');
    if (sp > 0) {
      method = pattern.substring(0, sp);
      path = pattern.substring(sp + 1).trim();
    }
    boolean exactRoot = false;
    boolean prefix = false;
    if (path.endsWith("/{$}")) {
      exactRoot = true;
      path = path.substring(0, path.length() - 3);
    } else if (path.endsWith("/")) {
      prefix = true;
    }
    List<String> segs = new ArrayList<>();
    for (String s : path.split("/")) {
      if (!s.isEmpty()) segs.add(s);
    }
    routes.add(new Route(method, segs, prefix, exactRoot, h, routes.size()));
    return this;
  }

  @Override
  public void handle(HttpExchange ex) throws IOException {
    try {
      dispatch(ex);
    } catch (Exception e) {
      // A handler throwing must not take down the listener: log it and drop the connection.
      LOG.error("http: panic serving {}: {}", ex.getRemoteAddress(), e.toString(), e);
      ex.close();
    }
  }

  private void dispatch(HttpExchange ex) throws Exception {
    String raw = ex.getRequestURI().getRawPath();
    if (raw == null || raw.isEmpty()) raw = "/";
    String method = ex.getRequestMethod();

    String clean = cleanPath(raw);
    if (!clean.equals(raw) && !"CONNECT".equals(method)) {
      String q = ex.getRequestURI().getRawQuery();
      Http.redirect(ex, clean + (q == null ? "" : "?" + q), 301);
      return;
    }

    List<String> segs = new ArrayList<>();
    for (String s : raw.split("/", -1)) {
      segs.add(unescape(s));
    }
    segs.remove(0); // leading ""
    boolean trailingSlash = raw.endsWith("/");
    if (trailingSlash && !segs.isEmpty()) {
      segs.remove(segs.size() - 1);
    }

    Route best = null;
    Map<String, String> bestVals = null;
    int bestScore = -1;
    TreeSet<String> allow = new TreeSet<>();
    for (Route r : routes) {
      Map<String, String> vals = new HashMap<>();
      int score = match(r, segs, trailingSlash, vals);
      if (score < 0) continue;
      if (r.method != null && !r.method.equals(method) && !("GET".equals(r.method) && "HEAD".equals(method))) {
        allow.add(r.method);
        if ("GET".equals(r.method)) allow.add("HEAD");
        continue;
      }
      if (score > bestScore || (score == bestScore && best != null && best.method == null && r.method != null)) {
        best = r;
        bestVals = vals;
        bestScore = score;
      }
    }
    if (best == null) {
      // "/reports" → "/reports/" when only the subtree pattern exists.
      if (!trailingSlash) {
        for (Route r : routes) {
          if (r.prefix && r.segs.equals(segs) && (r.method == null || r.method.equals(method)
              || ("GET".equals(r.method) && "HEAD".equals(method)))) {
            String q = ex.getRequestURI().getRawQuery();
            Http.redirect(ex, raw + "/" + (q == null ? "" : "?" + q), 301);
            return;
          }
        }
      }
      if (!allow.isEmpty()) {
        ex.getResponseHeaders().set("Allow", String.join(", ", allow));
        Http.error(ex, "Method Not Allowed", 405);
        return;
      }
      Http.notFound(ex);
      return;
    }
    String rest = "";
    if (best.prefix) {
      String prefix = "/" + String.join("/", best.segs) + (best.segs.isEmpty() ? "" : "/");
      String decoded = ex.getRequestURI().getPath();
      rest = decoded.length() >= prefix.length() ? decoded.substring(prefix.length()) : "";
    }
    best.h.handle(new Request(ex, bestVals, rest));
  }

  /** Returns a specificity score (higher = more specific) or -1 when the route does not match. */
  private static int match(Route r, List<String> segs, boolean trailingSlash, Map<String, String> vals) {
    if (r.exactRoot) {
      return (segs.isEmpty() && r.segs.isEmpty()) ? 10_000 : -1;
    }
    if (r.prefix) {
      if (segs.size() < r.segs.size()) return -1;
      if (segs.size() == r.segs.size() && !trailingSlash && !r.segs.isEmpty()) return -1;
      for (int i = 0; i < r.segs.size(); i++) {
        if (!segs.get(i).equals(r.segs.get(i))) return -1;
      }
      return r.segs.size() * 10;
    }
    if (trailingSlash || segs.size() != r.segs.size()) return -1;
    int score = 1000 + segs.size() * 10;
    for (int i = 0; i < segs.size(); i++) {
      String p = r.segs.get(i);
      if (p.startsWith("{") && p.endsWith("}")) {
        if (segs.get(i).isEmpty()) return -1;
        vals.put(p.substring(1, p.length() - 1), segs.get(i));
        score -= 1;
      } else if (!p.equals(segs.get(i))) {
        return -1;
      }
    }
    return score;
  }

  /** Normalises '.', '..' and duplicate slashes in a path, preserving any trailing slash. */
  static String cleanPath(String p) {
    if (p.isEmpty()) return "/";
    if (p.charAt(0) != '/') p = "/" + p;
    String[] parts = p.split("/");
    List<String> out = new ArrayList<>();
    for (String s : parts) {
      if (s.isEmpty() || s.equals(".")) continue;
      if (s.equals("..")) {
        if (!out.isEmpty()) out.remove(out.size() - 1);
        continue;
      }
      out.add(s);
    }
    String np = "/" + String.join("/", out);
    if (p.endsWith("/") && !np.equals("/")) np += "/";
    return np;
  }

  /** Percent-decoding of one path segment ('+' stays '+'). */
  static String unescape(String s) {
    if (s.indexOf('%') < 0) return s;
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '%' && i + 2 < s.length()) {
        try {
          b.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
          i += 2;
          continue;
        } catch (RuntimeException ignored) {
          // not a valid escape: keep literally
        }
      }
      byte[] cb = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
      b.write(cb, 0, cb.length);
    }
    return b.toString(StandardCharsets.UTF_8);
  }
}
