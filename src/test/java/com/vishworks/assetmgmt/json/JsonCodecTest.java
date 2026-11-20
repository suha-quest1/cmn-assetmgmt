package com.vishworks.assetmgmt.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vishworks.assetmgmt.models.BridgeResult;
import com.vishworks.assetmgmt.models.CallState;
import com.vishworks.assetmgmt.models.CapitalCallInput;
import com.vishworks.assetmgmt.models.LP;
import com.vishworks.assetmgmt.models.LPResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire format: field order and omitted empty fields, plain-notation doubles,
 * HTML-safe escaping, and which decoding mismatches are errors rather than silent coercions.
 */
class JsonCodecTest {

  @Test
  void omitemptyFieldsAndFieldOrder() {
    LPResponse r = new LPResponse();
    r.lpId = "lp-10";
    r.status = "defaulted";
    assertEquals("{\"lpId\":\"lp-10\",\"status\":\"defaulted\",\"amountUSD\":0,\"riskScore\":0}", JsonCodec.toJson(r));
    r.email = "lp-10@example.com";
    r.commitmentUSD = 20_000_000;
    r.drawAmountUSD = 5747126.44;
    r.followUps = 3;
    assertEquals("{\"lpId\":\"lp-10\",\"email\":\"lp-10@example.com\",\"commitmentUSD\":20000000,\"drawAmountUSD\":5747126.44,"
        + "\"status\":\"defaulted\",\"amountUSD\":0,\"riskScore\":0,\"followUps\":3}", JsonCodec.toJson(r));

    LP lp = new LP();
    lp.lpId = "lp-01";
    lp.commitmentUSD = 15e6;
    lp.email = "lp-01@example.com";
    assertEquals("{\"lpId\":\"lp-01\",\"commitmentUSD\":15000000,\"email\":\"lp-01@example.com\"}", JsonCodec.toJson(lp));

    CapitalCallInput in = new CapitalCallInput();
    in.callId = "CC-1";
    in.fundId = "F";
    in.targetAmountUSD = 5e7;
    in.deadlineDays = 10;
    assertEquals("{\"callId\":\"CC-1\",\"fundId\":\"F\",\"targetAmountUSD\":50000000,\"lpList\":null,\"deadlineDays\":10}", JsonCodec.toJson(in));
    in.secondsPerDay = 5;
    in.lpList = new ArrayList<>();
    assertTrue(JsonCodec.toJson(in).endsWith("\"lpList\":[],\"deadlineDays\":10,\"secondsPerDay\":5}"));
  }

  @Test
  void htmlSafeEscapingLikeGo() {
    Map<String, Object> m = new TreeMap<>();
    m.put("detail", "LP lp-01 -> committed ($5) <b> & \u2028");
    m.put("a", "tab\tnl\n\u0001");
    assertEquals("{\"a\":\"tab\\tnl\\n\\u0001\",\"detail\":\"LP lp-01 -\\u003e committed ($5) \\u003cb\\u003e \\u0026 \\u2028\"}",
        JsonCodec.toJson(m));
  }

  @Test
  void encodeAppendsNewlineAndNullsAreKept() {
    Map<String, Object> m = new TreeMap<>();
    m.put("bridgeResult", null);
    m.put("t", LocalDateTime.of(2026, 1, 2, 3, 4, 5, 600_000));
    assertEquals("{\"bridgeResult\":null,\"t\":\"2026-01-02T03:04:05.0006Z\"}\n", new String(JsonCodec.encode(m), StandardCharsets.UTF_8));
  }

  @Test
  void decodingFollowsGoRules() throws Exception {
    // unknown fields ignored, case-insensitive keys, null keeps the zero value
    CallState s = JsonCodec.fromJson("{\"CALLID\":\"CC-9\",\"fundId\":null,\"x\":1,\"bridgeResult\":null,\"gapPercent\":11.49}", CallState.class);
    assertEquals("CC-9", s.callId);
    assertEquals("", s.fundId);
    assertNull(s.bridgeResult);
    assertEquals(11.49, s.gapPercent);
    // JSON null document → zero value
    assertEquals("", JsonCodec.fromJson("null", CallState.class).callId);
    // type mismatches are errors, never silently coerced
    assertThrows(IOException.class, () -> JsonCodec.fromJson("{\"callId\":5}", CallState.class));
    assertThrows(IOException.class, () -> JsonCodec.fromJson("{\"deadlineDays\":\"10\"}", CallState.class));
    assertThrows(IOException.class, () -> JsonCodec.fromJson("{\"deadlineDays\":10.5}", CallState.class));
    assertThrows(IOException.class, () -> JsonCodec.fromJson("{\"targetAmountUSD\":\"1\"}", CallState.class));
    assertThrows(IOException.class, () -> JsonCodec.fromJson("{\"bridgeTriggered\":1}", CallState.class));
    // ints are accepted for float64 fields
    assertEquals(5e7, JsonCodec.fromJson("{\"targetAmountUSD\":50000000}", CallState.class).targetAmountUSD);
    BridgeResult br = JsonCodec.fromJson("{\"confirmationId\":\"B\",\"drawdownUSD\":1,\"feeUSD\":0.02}", BridgeResult.class);
    assertEquals(0.02, br.feeUSD);
  }

  @Test
  void decodeBodyErrors() {
    JsonCodec.DecodeException e = assertThrows(JsonCodec.DecodeException.class, () -> JsonCodec.decodeBody(new byte[0], CallState.class));
    assertEquals("EOF", e.getMessage());
    assertThrows(JsonCodec.DecodeException.class, () -> JsonCodec.decodeBody("{bad".getBytes(), CallState.class));
  }

  @Test
  void marshalIndentMatchesGo() {
    Map<String, Object> m = new TreeMap<>();
    m.put("b", List.of());
    m.put("a", Map.of());
    m.put("c", List.of(Map.of("x", 1), 2.5));
    m.put("d", null);
    assertEquals("{\n  \"a\": {},\n  \"b\": [],\n  \"c\": [\n    {\n      \"x\": 1\n    },\n    2.5\n  ],\n  \"d\": null\n}",
        JsonCodec.toJsonIndented(m));
  }
}
