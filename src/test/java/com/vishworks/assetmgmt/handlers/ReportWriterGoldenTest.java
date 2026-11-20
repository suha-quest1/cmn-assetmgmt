package com.vishworks.assetmgmt.handlers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.vishworks.assetmgmt.json.JsonCodec;
import com.vishworks.assetmgmt.models.BridgeResult;
import com.vishworks.assetmgmt.models.CallState;
import com.vishworks.assetmgmt.models.GPDecision;
import com.vishworks.assetmgmt.models.LPResponse;
import com.vishworks.assetmgmt.models.PortfolioRisk;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Golden-file test. The JSON and PDF reports in src/test/resources/golden are the frozen
 * output contract for the regulatory report: given the inputs recorded in each fixture,
 * {@link ReportWriter} must reproduce them byte for byte.
 *
 * <p>A diff here means the report documents changed. That is sometimes intended, but it is
 * never incidental — regenerate the fixtures deliberately, never to make the test pass.
 */
class ReportWriterGoldenTest {

  static byte[] resource(String name) throws Exception {
    try (InputStream in = ReportWriterGoldenTest.class.getResourceAsStream("/golden/" + name)) {
      return in.readAllBytes();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"CC-2026-4563", "CC-2026-9483"})
  void reproducesGoldenReports(String callId) throws Exception {
    byte[] goldenJson = resource(callId + ".json");
    byte[] goldenPdf = resource(callId + ".pdf");
    JsonNode doc = JsonCodec.MAPPER.readTree(goldenJson);

    CallState in = new CallState();
    in.callId = doc.get("callId").asText();
    in.fundId = doc.get("fundId").asText();
    in.settlementMode = doc.get("settlementMode").asText();
    JsonNode s = doc.get("summary");
    in.targetAmountUSD = s.get("targetAmountUSD").asDouble();
    in.totalCommitted = s.get("totalCommitted").asDouble();
    in.gapUSD = s.get("gapUSD").asDouble();
    in.gapPercent = s.get("gapPercent").asDouble();
    in.bridgeTriggered = s.get("bridgeTriggered").asBoolean();
    in.bridgeResult = JsonCodec.MAPPER.treeToValue(doc.get("bridgeResult"), BridgeResult.class);
    in.portfolioRisk = JsonCodec.MAPPER.treeToValue(doc.get("portfolioRisk"), PortfolioRisk.class);
    in.lpResponses = List.of(JsonCodec.MAPPER.treeToValue(doc.get("lpDetails"), LPResponse[].class));
    in.gpDecisions = doc.get("gpDecisions").isNull() ? null : List.of(JsonCodec.MAPPER.treeToValue(doc.get("gpDecisions"), GPDecision[].class));
    List<ReportWriter.LedgerRow> ledger = null;
    if (!doc.get("ledger").isNull()) {
      ledger = new ArrayList<>(List.of(JsonCodec.MAPPER.treeToValue(doc.get("ledger"), ReportWriter.LedgerRow[].class)));
    }
    String pik = doc.get("processInstanceKey").asText();
    ZonedDateTime now = ZonedDateTime.parse(doc.get("generatedAt").asText());

    assertEquals(new String(goldenJson, StandardCharsets.UTF_8), ReportWriter.renderJson(in, pik, ledger, now));
    assertArrayEquals(goldenPdf, ReportWriter.renderPdf(in, pik, ledger, now));
  }

  @org.junit.jupiter.api.Test
  void usdFormatting() {
    assertEquals("$50,000,000.00", ReportWriter.usd(50_000_000));
    assertEquals("$0.00", ReportWriter.usd(0));
    assertEquals("-$1,234.57", ReportWriter.usd(-1234.567));
    assertEquals("$999.99", ReportWriter.usd(999.99));
  }
}
