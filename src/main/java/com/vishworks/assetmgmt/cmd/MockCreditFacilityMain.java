package com.vishworks.assetmgmt.cmd;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vishworks.assetmgmt.http.Http;
import com.vishworks.assetmgmt.http.Router;
import com.vishworks.assetmgmt.json.JsonCodec;
import com.vishworks.assetmgmt.util.Env;
import com.vishworks.assetmgmt.util.Format;
import com.vishworks.assetmgmt.util.Log;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/** Bridge / subscription credit line HTTP stub (port of cmd/mockcreditfacility). */
public final class MockCreditFacilityMain {
  private MockCreditFacilityMain() {}

  /** Drawdown request body. */
  public static class DrawdownReq {
    @JsonProperty("callId") public String callId = "";
    @JsonProperty("amount") public double amount;
  }

  public static void main(String[] args) throws Exception {
    String addr = Env.get("LISTEN_ADDR", ":8082");
    Log.printf("Mock Credit Facility listening on %s", addr);
    try {
      Http.listen(addr, routes());
    } catch (IOException e) {
      Log.fatal(e);
    }
  }

  static Router routes() {
    Router mux = new Router();
    // POST /drawdown — accepts a drawdown request and returns confirmation
    mux.handle("/drawdown", r -> {
      if (!"POST".equals(r.method())) {
        Http.error(r.ex, "method not allowed", 405);
        return;
      }
      DrawdownReq req;
      try {
        req = JsonCodec.decodeBody(r.body(), DrawdownReq.class);
      } catch (JsonCodec.DecodeException e) {
        Http.error(r.ex, "invalid JSON", 400);
        return;
      }
      // Simulate a 1.5% facility fee
      double fee = Format.round(req.amount * 0.015 * 100) / 100;
      String confirmationId = Format.sprintf("BRIDGE-%s-%d", req.callId, System.currentTimeMillis());
      Log.printf("[MOCK-CREDIT] Drawdown: callId=%s amount=$%.2f fee=$%.2f confirmation=%s", req.callId, req.amount, fee, confirmationId);
      Map<String, Object> out = new TreeMap<>();
      out.put("confirmationId", confirmationId);
      out.put("drawdownUSD", req.amount);
      out.put("feeUSD", fee);
      Http.writeJSON(r.ex, 200, out);
    });
    // GET / — health check (catch-all, any method)
    mux.handle("/", r -> Http.writeJSON(r.ex, 200, new TreeMap<>(Map.of("service", "mock-credit-facility", "status", "ok"))));
    return mux;
  }
}
