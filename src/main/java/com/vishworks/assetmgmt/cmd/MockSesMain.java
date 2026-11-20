package com.vishworks.assetmgmt.cmd;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.vishworks.assetmgmt.http.Http;
import com.vishworks.assetmgmt.http.Router;
import com.vishworks.assetmgmt.json.JsonCodec;
import com.vishworks.assetmgmt.util.Env;
import com.vishworks.assetmgmt.util.Format;
import com.vishworks.assetmgmt.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stand-in for AWS SES / Slack: accepts POST /send and shows every message in an
 * auto-refreshing log viewer (port of cmd/mockses).
 */
public final class MockSesMain {
  private MockSesMain() {}

  @JsonPropertyOrder({"timestamp", "payload"})
  static final class Entry {
    @JsonProperty("timestamp") public String timestamp;
    @JsonProperty("payload") public Map<String, Object> payload;

    Entry(String timestamp, Map<String, Object> payload) {
      this.timestamp = timestamp;
      this.payload = payload;
    }
  }

  private static final Object MU = new Object();
  private static List<Entry> emails; // stays null until the first message; see the /messages handler

  public static void main(String[] args) throws Exception {
    String addr = Env.get("LISTEN_ADDR", ":8081");
    byte[] page;
    try (InputStream in = MockSesMain.class.getResourceAsStream("/mockses/index.html")) {
      page = in.readAllBytes();
    }
    Router mux = routes(page);
    Log.printf("Mock SES listening on %s", addr);
    try {
      Http.listen(addr, mux);
    } catch (IOException e) {
      Log.fatal(e);
    }
  }

  static Router routes(byte[] page) {
    Router mux = new Router();
    mux.handle("POST /send", r -> {
      Map<String, Object> p;
      try {
        p = decode(r.body());
      } catch (IOException e) {
        Http.error(r.ex, "invalid JSON", 400);
        return;
      }
      synchronized (MU) {
        if (emails == null) {
          emails = new ArrayList<>();
        }
        emails.add(new Entry(Format.rfc3339Nano(ZonedDateTime.now()), p));
      }
      Object type = p == null ? null : p.get("type");
      Object to = p == null ? null : p.get("to");
      Object subject = p == null ? null : p.get("subject");
      Log.printf("[MOCK-SES] %-20v to=%v subject=%v", type, to, subject);
      Http.writeJSON(r.ex, 200, Map.of("status", "sent"));
    });
    mux.handle("GET /emails", r -> {
      byte[] out;
      synchronized (MU) {
        out = emails == null ? "[]".getBytes(StandardCharsets.UTF_8) : JsonCodec.encode(emails);
      }
      Http.send(r.ex, 200, "application/json", out);
    });
    mux.handle("GET /{$}", r -> Http.send(r.ex, 200, "text/html; charset=utf-8", page));
    return mux;
  }

  /** json.Decoder.Decode into map[string]any (a JSON null gives a nil map). */
  static Map<String, Object> decode(byte[] body) throws IOException {
    String s = new String(body, StandardCharsets.UTF_8);
    if (s.isBlank()) {
      throw new IOException("EOF");
    }
    Map<String, Object> m = JsonCodec.MAPPER.readValue(s, new TypeReference<TreeMap<String, Object>>() {});
    return m;
  }

  /** For tests. */
  static void reset() {
    synchronized (MU) {
      emails = null;
    }
  }
}
