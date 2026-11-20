package com.shinro.migration.cmnassetmgmt.activities;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/** Shared collaborators of the activities: database, outbound HTTP and configuration. */
public class Support {
  static final ObjectMapper MAPPER =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  public Db db;
  public String sesUrl = "http://localhost:8081";
  public String mlScorerUrl = "http://localhost:5001";
  public String creditUrl = "http://localhost:8082";
  public String reportDir = "./reports";
  /** Public URL of the API server / LP portal. */
  public String portalUrl = "http://localhost:8090";
  /** Public URL of the Temporal UI. */
  public String temporalUiUrl = "http://localhost:8233";
  public HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
  public Duration httpTimeout = Duration.ofSeconds(20);

  public Support() {}

  /** Builds the configuration from environment variables, with the source defaults. */
  public static Support fromEnv() {
    Support s = new Support();
    s.db = new Db(env("DATABASE_URL", "postgres://assetmgmt:assetmgmt@localhost:5433/assetmgmt?sslmode=disable"));
    s.sesUrl = env("SES_URL", s.sesUrl);
    s.mlScorerUrl = env("ML_SCORER_URL", s.mlScorerUrl);
    s.creditUrl = env("CREDIT_FACILITY_URL", s.creditUrl);
    s.reportDir = env("REPORT_DIR", s.reportDir);
    s.portalUrl = env("PORTAL_PUBLIC_URL", s.portalUrl);
    s.temporalUiUrl = env("TEMPORAL_UI_URL", s.temporalUiUrl);
    return s;
  }

  static String env(String k, String def) {
    String v = System.getenv(k);
    return v == null || v.isEmpty() ? def : v;
  }

  /** Builds a variable map from alternating key/value arguments. */
  static Map<String, Object> m(Object... kv) {
    Map<String, Object> out = new TreeMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      out.put((String) kv[i], kv[i + 1]);
    }
    return out;
  }

  /** Appends a row to the audit trail. A failure is logged, never fatal. */
  public void event(String callId, String lpId, String step, String detail) {
    Object lp = (lpId == null || lpId.isEmpty()) ? null : lpId;
    try {
      // Plain INSERT as in the source. A retried activity adds a second audit row.
      db.exec("INSERT INTO call_events (call_id, lp_id, step, detail) VALUES ($1,$2,$3,$4)", callId, lp, step, detail);
    } catch (SQLException e) {
      System.err.println("event insert: " + e.getMessage());
    }
  }

  public void setCallStatus(String callId, String status) {
    try {
      db.exec("UPDATE capital_calls SET status=$2, updated_at=NOW() WHERE call_id=$1", callId, status);
    } catch (SQLException e) {
      System.err.println("set status: " + e.getMessage());
    }
  }

  /**
   * POSTs a JSON body. A non-2xx answer raises an IOException. The idempotencyKey goes out as
   * the Idempotency-Key header. The downstream must dedupe on it for retries to be safe.
   */
  public <T> T postJSON(String url, Object in, TypeReference<T> out, String idempotencyKey)
      throws IOException, InterruptedException {
    byte[] b = MAPPER.writeValueAsString(in).getBytes(StandardCharsets.UTF_8);
    HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
        .timeout(httpTimeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(b));
    if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
      rb.header("Idempotency-Key", idempotencyKey);
    }
    HttpResponse<String> resp;
    try {
      resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IOException("Post \"" + url + "\": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
    }
    if (resp.statusCode() >= 300) {
      throw new IOException("POST " + url + ": HTTP " + resp.statusCode() + ": " + resp.body());
    }
    if (out != null) {
      return MAPPER.readValue(resp.body(), out);
    }
    return null;
  }

  /**
   * Sends one message through the mock SES. A failure is logged and swallowed, as in the
   * source. Idempotency key: the caller passes callId:lpId[:stage]. SES must dedupe on it.
   */
  public void email(Map<String, Object> payload, String idempotencyKey) {
    try {
      postJSON(sesUrl + "/send", payload, null, idempotencyKey);
    } catch (IOException e) {
      System.err.println("[ses] send failed: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      System.err.println("[ses] send failed: interrupted");
    }
  }

  static String json(Object o) throws JsonProcessingException {
    return MAPPER.writeValueAsString(o);
  }
}
