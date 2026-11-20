package com.vishworks.assetmgmt.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Static file serving equivalent to http.StripPrefix(prefix, http.FileServer(http.Dir(root)))
 * as used for GET /reports/: directory listing, Content-Type by extension, Last-Modified /
 * If-Modified-Since, single byte ranges, and the same redirects for directories.
 */
public final class FileServer implements Router.Handler {
  private static final DateTimeFormatter HTTP_DATE =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

  private static final Map<String, String> TYPES = Map.ofEntries(
      Map.entry(".pdf", "application/pdf"),
      Map.entry(".json", "application/json"),
      Map.entry(".html", "text/html; charset=utf-8"),
      Map.entry(".htm", "text/html; charset=utf-8"),
      Map.entry(".txt", "text/plain; charset=utf-8"),
      Map.entry(".css", "text/css; charset=utf-8"),
      Map.entry(".js", "text/javascript; charset=utf-8"),
      Map.entry(".png", "image/png"),
      Map.entry(".jpg", "image/jpeg"),
      Map.entry(".jpeg", "image/jpeg"),
      Map.entry(".gif", "image/gif"),
      Map.entry(".svg", "image/svg+xml"),
      Map.entry(".xml", "text/xml; charset=utf-8"));

  private final Path root;

  public FileServer(Path root) {
    this.root = root;
  }

  @Override
  public void handle(Router.Request r) throws Exception {
    HttpExchange ex = r.ex;
    String upath = "/" + r.rest; // FileServer: ensure leading slash
    String urlPath = upath;
    String clean = Router.cleanPath(upath);
    serveFile(ex, clean, urlPath);
  }

  private void serveFile(HttpExchange ex, String name, String urlPath) throws IOException {
    // redirect .../index.html to .../
    if (urlPath.endsWith("/index.html")) {
      localRedirect(ex, "./");
      return;
    }
    Path p = root.resolve(name.substring(1)).normalize();
    if (!p.startsWith(root.normalize())) {
      Http.notFound(ex);
      return;
    }
    if (!Files.exists(p)) {
      Http.notFound(ex);
      return;
    }
    boolean dir = Files.isDirectory(p);
    if (dir) {
      if (!urlPath.endsWith("/")) {
        localRedirect(ex, base(urlPath) + "/");
        return;
      }
    } else if (urlPath.endsWith("/")) {
      localRedirect(ex, "../" + base(urlPath));
      return;
    }
    if (!Files.isReadable(p)) {
      Http.error(ex, "403 Forbidden", 403);
      return;
    }
    if (dir) {
      Path index = p.resolve("index.html");
      if (Files.isRegularFile(index)) {
        serveContent(ex, index);
        return;
      }
      Instant mod = Files.getLastModifiedTime(p).toInstant();
      if (notModified(ex, mod)) {
        writeNotModified(ex);
        return;
      }
      ex.getResponseHeaders().set("Last-Modified", HTTP_DATE.format(mod));
      dirList(ex, p);
      return;
    }
    serveContent(ex, p);
  }

  private static String base(String path) {
    String s = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    int i = s.lastIndexOf('/');
    return i >= 0 ? s.substring(i + 1) : s;
  }

  private static void localRedirect(HttpExchange ex, String newPath) throws IOException {
    String q = ex.getRequestURI().getRawQuery();
    if (q != null) newPath += "?" + q;
    ex.getResponseHeaders().set("Location", newPath);
    ex.sendResponseHeaders(301, -1);
    ex.close();
  }

  private static boolean notModified(HttpExchange ex, Instant mod) {
    String ims = ex.getRequestHeaders().getFirst("If-Modified-Since");
    if (ims == null || !("GET".equals(ex.getRequestMethod()) || "HEAD".equals(ex.getRequestMethod()))) {
      return false;
    }
    try {
      Instant t = ZonedDateTime.parse(ims, HTTP_DATE).toInstant();
      return mod.getEpochSecond() <= t.getEpochSecond();
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static void writeNotModified(HttpExchange ex) throws IOException {
    ex.getResponseHeaders().remove("Content-Type");
    ex.getResponseHeaders().remove("Content-Length");
    ex.sendResponseHeaders(304, -1);
    ex.close();
  }

  private static void dirList(HttpExchange ex, Path dir) throws IOException {
    List<String> names = new ArrayList<>();
    try (Stream<Path> s = Files.list(dir)) {
      s.forEach(f -> names.add(f.getFileName().toString() + (Files.isDirectory(f) ? "/" : "")));
    }
    names.sort(null);
    StringBuilder b = new StringBuilder("<!doctype html>\n<meta name=\"viewport\" content=\"width=device-width\">\n<pre>\n");
    for (String n : names) {
      String href;
      try {
        href = new URI(null, null, n, null).toASCIIString();
      } catch (java.net.URISyntaxException e) {
        href = n;
      }
      if (href.contains(":") && !href.contains("/")) {
        href = "./" + href; // a name like "a:b" would otherwise be read as a URL scheme
      }
      b.append("<a href=\"").append(href).append("\">").append(Http.htmlEscape(n)).append("</a>\n");
    }
    b.append("</pre>\n");
    Http.send(ex, 200, "text/html; charset=utf-8", b.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void serveContent(HttpExchange ex, Path f) throws IOException {
    Instant mod = Files.getLastModifiedTime(f).toInstant();
    if (notModified(ex, mod)) {
      writeNotModified(ex);
      return;
    }
    String name = f.getFileName().toString();
    int dot = name.lastIndexOf('.');
    String ctype = dot >= 0 ? TYPES.get(name.substring(dot).toLowerCase(Locale.ROOT)) : null;
    if (ctype == null) {
      ctype = sniff(f);
    }
    long size = Files.size(f);
    var h = ex.getResponseHeaders();
    h.set("Content-Type", ctype);
    if (mod.getEpochSecond() > 0) {
      h.set("Last-Modified", HTTP_DATE.format(mod));
    }
    long start = 0, len = size;
    int status = 200;
    String range = ex.getRequestHeaders().getFirst("Range");
    if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
      String spec = range.substring(6).trim();
      int dash = spec.indexOf('-');
      try {
        if (dash == 0) {
          long n = Long.parseLong(spec.substring(1));
          if (n > size) n = size;
          start = size - n;
          len = n;
        } else if (dash > 0) {
          start = Long.parseLong(spec.substring(0, dash));
          String e = spec.substring(dash + 1);
          long end = e.isEmpty() ? size - 1 : Math.min(Long.parseLong(e), size - 1);
          if (start >= size || end < start) {
            h.set("Content-Range", "bytes */" + size);
            Http.error(ex, "invalid range: failed to overlap", 416);
            return;
          }
          len = end - start + 1;
        }
        status = 206;
        h.set("Content-Range", "bytes " + start + "-" + (start + len - 1) + "/" + size);
      } catch (NumberFormatException e) {
        Http.error(ex, "invalid range", 416);
        return;
      }
    }
    h.set("Accept-Ranges", "bytes");
    if ("HEAD".equals(ex.getRequestMethod())) {
      h.set("Content-Length", Long.toString(len));
      ex.sendResponseHeaders(status, -1);
      ex.close();
      return;
    }
    ex.sendResponseHeaders(status, len == 0 ? -1 : len);
    try (InputStream in = Files.newInputStream(f); OutputStream os = ex.getResponseBody()) {
      in.skipNBytes(start);
      byte[] buf = new byte[8192];
      long left = len;
      while (left > 0) {
        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
        if (n < 0) break;
        os.write(buf, 0, n);
        left -= n;
      }
    } catch (NoSuchFileException | AccessDeniedException e) {
      // file vanished between stat and read
    }
    ex.close();
  }

  private static String sniff(Path f) throws IOException {
    byte[] head;
    try (InputStream in = Files.newInputStream(f)) {
      head = in.readNBytes(512);
    }
    String s = new String(head, StandardCharsets.ISO_8859_1).stripLeading().toLowerCase(Locale.ROOT);
    if (s.startsWith("%pdf-")) return "application/pdf";
    if (s.startsWith("<!doctype html") || s.startsWith("<html")) return "text/html; charset=utf-8";
    for (byte b : head) {
      if ((b >= 0 && b < 0x09) || b == 0x0b || (b > 0x0d && b < 0x1b) || (b > 0x1b && b < 0x20)) {
        return "application/octet-stream";
      }
    }
    return "text/plain; charset=utf-8";
  }
}
