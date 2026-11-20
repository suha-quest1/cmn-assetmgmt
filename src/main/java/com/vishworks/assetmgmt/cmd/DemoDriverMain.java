package com.vishworks.assetmgmt.cmd;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vishworks.assetmgmt.json.JsonCodec;
import com.vishworks.assetmgmt.util.Errors;
import com.vishworks.assetmgmt.util.Format;
import com.vishworks.assetmgmt.util.Log;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the scripted end-to-end demo against the API server (port of cmd/demodriver):
 *
 * <ul>
 *   <li>issues a capital call to the 10 seeded LPs (compressed time: N s = 1 day)
 *   <li>lp-01..07 commit promptly on a staggered timer
 *   <li>lp-08 commits but is high-risk → GP approval gate (Tasklist / API)
 *   <li>lp-09 misses D+3, gets the email follow-up, then commits late
 *   <li>lp-10 never responds → email D+3, call D+7, legal notice D+10 → default → liquidity
 *       gap &gt; 10% → bridge credit-line drawdown
 * </ul>
 *
 * Flags, accepted as -name value, -name=value or --name: -api, -seconds-per-day, -target,
 * -deadline-days, -gp-decision.
 */
public final class DemoDriverMain {
  private DemoDriverMain() {}

  static String api;
  static final HttpClient HTTP = HttpClient.newHttpClient();

  /** call(): JSON request, error for HTTP ≥ 300, optional JSON decoding into out. */
  static <T> T call(String method, String path, Object in, Class<T> out) throws IOException, InterruptedException {
    HttpRequest.BodyPublisher body = in == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(JsonCodec.toJson(in), StandardCharsets.UTF_8);
    HttpRequest req = HttpRequest.newBuilder(URI.create(api + path))
        .header("Content-Type", "application/json")
        .method(method, body)
        .build();
    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (resp.statusCode() >= 300) {
      throw new IOException(Format.sprintf("%s %s: HTTP %d %s", method, path, resp.statusCode(), resp.body()));
    }
    if (out != null) {
      return JsonCodec.fromJson(resp.body(), out);
    }
    return null;
  }

  public static class Started {
    @JsonProperty("callId") public String callId = "";
    @JsonProperty("processInstanceKey") public String pik = "";
    @JsonProperty("operateUrl") public String operateUrl = "";
  }

  public static class LpRow {
    @JsonProperty("lp_id") public String lpId = "";
    @JsonProperty("draw_amount_usd") public double draw;
    @JsonProperty("status") public String status = "";
    @JsonProperty("demo_behavior") public String behavior = "";
  }

  public static class CallInfo {
    @JsonProperty("status") public String status = "";
    @JsonProperty("process_instance_key") public String pik = "";
    @JsonProperty("report_path") public String report = "";
  }

  public static class Escalation {
    @JsonProperty("lp_id") public String lpId = "";
    @JsonProperty("status") public String status = "";
  }

  public static class Detail {
    @JsonProperty("call") public CallInfo call = new CallInfo();
    @JsonProperty("lps") public List<LpRow> lps;
    @JsonProperty("escalations") public List<Escalation> escalations;
  }

  /** Parsed command-line flags. */
  static final class Flags {
    String api;
    int spd = 5;
    double target = 50_000_000;
    int deadline = 10;
    String gp = "";
  }

  static Flags parseFlags(String[] args, String defApi) {
    Flags f = new Flags();
    f.api = defApi;
    Map<String, String> usage = new LinkedHashMap<>();
    usage.put("api", "API server base URL (default \"" + defApi + "\")");
    usage.put("deadline-days", "LP response deadline in days (default 10)");
    usage.put("gp-decision", "auto-answer the GP approval gate: \"waive\" or \"enforce\" (default: leave it for Tasklist)");
    usage.put("seconds-per-day", "demo time compression: seconds per business day (default 5)");
    usage.put("target", "capital call target (USD) (default 5e+07)");
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      if (!a.startsWith("-") || a.equals("-") ) {
        break; // the first non-flag argument ends flag parsing
      }
      if (a.equals("--")) {
        break;
      }
      String name = a.startsWith("--") ? a.substring(2) : a.substring(1);
      String value = null;
      int eq = name.indexOf('=');
      if (eq >= 0) {
        value = name.substring(eq + 1);
        name = name.substring(0, eq);
      }
      if (name.equals("h") || name.equals("help")) {
        printUsage(usage);
        System.exit(0);
      }
      if (!usage.containsKey(name)) {
        System.err.println("flag provided but not defined: -" + name);
        printUsage(usage);
        System.exit(2);
      }
      if (value == null) {
        if (i + 1 >= args.length) {
          System.err.println("flag needs an argument: -" + name);
          printUsage(usage);
          System.exit(2);
        }
        value = args[++i];
      }
      try {
        switch (name) {
          case "api" -> f.api = value;
          case "seconds-per-day" -> f.spd = Integer.parseInt(value);
          case "target" -> f.target = Double.parseDouble(value);
          case "deadline-days" -> f.deadline = Integer.parseInt(value);
          case "gp-decision" -> f.gp = value;
          default -> { }
        }
      } catch (NumberFormatException e) {
        System.err.println("invalid value \"" + value + "\" for flag -" + name + ": parse error");
        printUsage(usage);
        System.exit(2);
      }
    }
    return f;
  }

  private static void printUsage(Map<String, String> usage) {
    System.err.println("Usage of demodriver:");
    usage.forEach((k, v) -> System.err.println("  -" + k + "\n    \t" + v));
  }

  public static void main(String[] args) throws Exception {
    String def = System.getenv("API_URL");
    if (def == null || def.isEmpty()) {
      def = "http://localhost:8090";
    }
    Flags fl = parseFlags(args, def);
    api = fl.api;
    final int spd = fl.spd;

    Map<String, Object> startBody = new java.util.TreeMap<>();
    startBody.put("fundId", "FUND-GROWTH-IV");
    startBody.put("targetAmountUSD", fl.target);
    startBody.put("deadlineDays", fl.deadline);
    startBody.put("secondsPerDay", spd);
    Started started;
    try {
      started = call("POST", "/api/capital-calls", startBody, Started.class);
    } catch (IOException e) {
      Log.fatal(e);
      return;
    }
    Log.printf("▶ capital call %s started (process instance %s)", started.callId, started.pik);
    Log.printf("  Operate:   %s", started.operateUrl);
    Log.printf("  Dashboard: %s/?call=%s", api, started.callId);

    Detail d = null;
    for (int i = 0; ; i++) {
      try {
        d = call("GET", "/api/capital-calls/" + started.callId, null, Detail.class);
        if (d.lps != null && !d.lps.isEmpty()) {
          break;
        }
      } catch (IOException e) {
        // not there yet
      }
      if (i > 60) {
        Log.fatal("capital call never appeared — is the worker running?");
      }
      Thread.sleep(1000);
    }
    final Duration day = Duration.ofSeconds(spd);
    final String callId = started.callId;

    final Instant start = Instant.now();
    int k = 0;
    for (LpRow lp : d.lps) {
      switch (lp.behavior) {
        case "prompt" -> {
          at(start, Duration.ofSeconds(2 + k), lp, "", callId);
          k++;
        }
        case "highrisk" -> at(start, day.multipliedBy(2), lp, lp.lpId + " (flagged high-risk by the ML model) responds", callId);
        case "late" -> at(start, day.multipliedBy(5), lp, lp.lpId + " responds late, after the D+3 email follow-up", callId);
        case "silent" -> Log.printf("  • %s will stay silent → follow-ups at D+3/D+7/D+10, then default", lp.lpId);
        default -> { }
      }
    }

    Log.printf("⏱  %d s per day: follow-ups at ~%ds/%ds/%ds, deadline at ~%ds", spd, 3 * spd, 7 * spd, 10 * spd, fl.deadline * spd);
    Set<String> gpDone = new HashSet<>();
    String last = "";
    while (true) {
      Thread.sleep(2000);
      try {
        d = call("GET", "/api/capital-calls/" + callId, null, Detail.class);
      } catch (IOException e) {
        continue;
      }
      if (!d.call.status.equals(last)) {
        Log.printf("● call status: %s", d.call.status);
        last = d.call.status;
      }
      for (Escalation e : d.escalations == null ? new ArrayList<Escalation>() : d.escalations) {
        if (!e.status.equals("pending") || gpDone.contains(e.lpId)) {
          continue;
        }
        gpDone.add(e.lpId);
        if (fl.gp.isEmpty()) {
          Log.printf("⚑ GP approval gate open for %s → complete it in Tasklist (http://localhost:8080/tasklist, demo/demo)", e.lpId);
          Log.printf("   or: curl -XPOST %s/api/capital-calls/%s/gp-decision -H 'Content-Type: application/json' -d '{\"lpId\":\"%s\",\"action\":\"waive\",\"gpName\":\"Jane Doe\"}'",
              api, callId, e.lpId);
          continue;
        }
        Thread.sleep(3000);
        try {
          Map<String, Object> b = new java.util.TreeMap<>();
          b.put("lpId", e.lpId);
          b.put("action", fl.gp);
          b.put("gpName", "Demo Driver GP");
          call("POST", "/api/capital-calls/" + callId + "/gp-decision", b, null);
          Log.printf("⚑ GP decision for %s: %s", e.lpId, fl.gp);
        } catch (IOException ex) {
          Log.printf("  ✗ gp decision: %v", Errors.message(ex));
        }
      }
      if (d.call.status.equals("completed") || d.call.status.equals("cancelled")) {
        Log.printf("■ done in %s. Report: %s/reports/%s.pdf", Format.duration(Format.roundToSecond(Duration.between(start, Instant.now()))),
            api, callId);
        return;
      }
    }
  }

  /** Commits an LP once the given offset from start has elapsed, on its own virtual thread. */
  static void at(Instant start, Duration offset, LpRow lp, String note, String callId) {
    Thread.ofVirtual().start(() -> {
      try {
        Duration wait = Duration.between(Instant.now(), start.plus(offset));
        if (!wait.isNegative()) {
          Thread.sleep(wait);
        }
        if (!note.isEmpty()) {
          Log.printf("  … %s", note);
        }
        commit(lp, callId);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
  }

  static void commit(LpRow lp, String callId) {
    try {
      Map<String, Object> b = new java.util.TreeMap<>();
      b.put("lpId", lp.lpId);
      b.put("amount", lp.draw);
      call("POST", "/api/capital-calls/" + callId + "/lp-response", b, null);
    } catch (Exception e) {
      Log.printf("  ✗ %s: %v", lp.lpId, Errors.message(e));
      return;
    }
    Log.printf("  ✓ %s committed $%.0f", lp.lpId, lp.draw);
  }
}
