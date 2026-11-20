package com.vishworks.assetmgmt.handlers;

import static com.vishworks.assetmgmt.util.Format.sprintf;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.vishworks.assetmgmt.json.JsonCodec;
import com.vishworks.assetmgmt.models.CallState;
import com.vishworks.assetmgmt.models.GPDecision;
import com.vishworks.assetmgmt.models.LPResponse;
import com.vishworks.assetmgmt.report.Pdf;
import com.vishworks.assetmgmt.util.Format;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Regulatory liquidity report, rendered as both JSON and PDF. */
final class ReportWriter {
  private ReportWriter() {}

  /** usd formats a float as $1,234,567.89 (thousands separators). */
  static String usd(double f) {
    String s = Format.fixed(f, 2);
    String intPart = s.substring(0, s.length() - 3), dec = s.substring(s.length() - 3);
    String neg = "";
    if (!intPart.isEmpty() && intPart.charAt(0) == '-') {
      neg = "-";
      intPart = intPart.substring(1);
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < intPart.length(); i++) {
      if (i > 0 && (intPart.length() - i) % 3 == 0) {
        out.append(',');
      }
      out.append(intPart.charAt(i));
    }
    return neg + "$" + out + dec;
  }

  @JsonPropertyOrder({"lpId", "debit", "credit", "amountUSD", "entryType", "wireReference"})
  static final class LedgerRow {
    @JsonProperty("lpId") @JsonInclude(JsonInclude.Include.NON_EMPTY) public String lpId = "";
    @JsonProperty("debit") public String debit = "";
    @JsonProperty("credit") public String credit = "";
    @JsonProperty("amountUSD") public double amount;
    @JsonProperty("entryType") public String type = "";
    @JsonProperty("wireReference") public String ref = "";
  }

  /** Writes reports/{callId}.json and .pdf; returns {jsonUrlPath, pdfUrlPath}. */
  static String[] writeReport(Handlers h, CallState in, String pik) throws Exception {
    List<LedgerRow> ledger = h.db.query(
        "SELECT COALESCE(lp_id,''), debit_account, credit_account, amount_usd::float8, entry_type, COALESCE(wire_reference,'') FROM ledger_entries WHERE call_id=$1 ORDER BY id",
        rs -> {
          LedgerRow r = new LedgerRow();
          r.lpId = rs.getString(1);
          r.debit = rs.getString(2);
          r.credit = rs.getString(3);
          r.amount = rs.getDouble(4);
          r.type = rs.getString(5);
          r.ref = rs.getString(6);
          return r;
        },
        in.callId);
    if (ledger.isEmpty()) {
      ledger = null; // an empty ledger is reported as "ledger": null, not []
    }

    ZonedDateTime now = Handlers.nowUtc();
    Path dir = Path.of(h.reportDir);
    Files.createDirectories(dir);
    Path jsonPath = dir.resolve(in.callId + ".json");
    Files.writeString(jsonPath, renderJson(in, pik, ledger, now), StandardCharsets.UTF_8);
    byte[] pdf = renderPdf(in, pik, ledger, now);
    Path pdfPath = dir.resolve(in.callId + ".pdf");
    Files.write(pdfPath, pdf);
    return new String[] {"/reports/" + in.callId + ".json", "/reports/" + in.callId + ".pdf"};
  }

  /**
   * The JSON report document, pretty-printed with sorted keys. Byte-compared against
   * {@code src/test/resources/golden} by {@code ReportWriterGoldenTest}.
   */
  static String renderJson(CallState in, String pik, List<LedgerRow> ledger, ZonedDateTime now) {
    Map<String, Object> summary = new TreeMap<>();
    summary.put("targetAmountUSD", in.targetAmountUSD);
    summary.put("totalCommitted", in.totalCommitted);
    summary.put("gapUSD", in.gapUSD);
    summary.put("gapPercent", in.gapPercent);
    summary.put("bridgeTriggered", in.bridgeTriggered);
    Map<String, Object> doc = new TreeMap<>();
    doc.put("reportType", "LiquidityReport");
    doc.put("generatedAt", Format.rfc3339Utc(now.toInstant()));
    doc.put("orchestrator", "Camunda 8");
    doc.put("processInstanceKey", pik);
    doc.put("callId", in.callId);
    doc.put("fundId", in.fundId);
    doc.put("settlementMode", in.settlementMode);
    doc.put("summary", summary);
    doc.put("bridgeResult", in.bridgeResult);
    doc.put("portfolioRisk", in.portfolioRisk);
    doc.put("lpDetails", in.lpResponses);
    doc.put("gpDecisions", in.gpDecisions);
    doc.put("ledger", ledger);
    return JsonCodec.toJsonIndented(doc);
  }

  /** The PDF report. */
  static byte[] renderPdf(CallState in, String pik, List<LedgerRow> ledger, ZonedDateTime now) {
    Pdf p = new Pdf();
    p.title("Capital Call Liquidity Report");
    p.text(sprintf("Call %s   |   Fund %s   |   Generated %s", in.callId, in.fundId,
        sprintf("%04d-%02d-%02d %02d:%02d UTC", now.getYear(), now.getMonthValue(), now.getDayOfMonth(), now.getHour(), now.getMinute())));
    p.text(sprintf("Orchestrated by Camunda 8 - process instance %s   |   settlement mode: %s", pik, in.settlementMode));
    p.heading("1. Liquidity summary");
    p.mono(sprintf("%-28s %20s", "Target capital call", usd(in.targetAmountUSD)));
    p.mono(sprintf("%-28s %20s", "LP capital committed", usd(in.totalCommitted)));
    p.mono(sprintf("%-28s %20s  (%.2f%%)", "Liquidity gap", usd(in.gapUSD), in.gapPercent));
    if (in.bridgeResult != null) {
      p.mono(sprintf("%-28s %20s  fee %s", "Bridge drawdown", usd(in.bridgeResult.drawdownUSD), usd(in.bridgeResult.feeUSD)));
      p.mono(sprintf("%-28s %s", "Facility confirmation", in.bridgeResult.confirmationId));
    } else {
      p.mono(sprintf("%-28s %s", "Bridge drawdown", "not required (gap <= 10%)"));
    }
    p.heading("2. LP responses and default-risk scores");
    p.mono(sprintf("%-7s %-10s %16s %16s %6s %9s", "LP", "Status", "Draw", "Funded", "Risk", "FollowUps"));
    for (LPResponse r : Handlers.nz(in.lpResponses)) {
      p.mono(sprintf("%-7s %-10s %16s %16s %6.2f %9d", r.lpId, r.status, usd(r.drawAmountUSD), usd(r.amountUSD), r.riskScore, r.followUps));
    }
    if (in.portfolioRisk != null) {
      p.heading("3. Portfolio risk");
      p.mono(sprintf("Concentration (HHI): %.4f   Avg risk: %.4f   Expected loss: %s", in.portfolioRisk.concentrationScore,
          in.portfolioRisk.averageRiskScore, usd(in.portfolioRisk.expectedLossUSD)));
      p.mono(sprintf("Top risky LPs: %s", Format.slice(in.portfolioRisk.topRiskyLPs)));
    }
    p.heading("4. GP escalations (human-in-the-loop)");
    List<GPDecision> decisions = Handlers.nz(in.gpDecisions);
    if (decisions.isEmpty()) {
      p.text("No LP exceeded the 0.70 risk threshold.");
    }
    for (GPDecision d : decisions) {
      p.mono(sprintf("%-7s risk %.2f -> %-8s by %s", d.lpId, d.riskScore, d.action, d.gpName));
    }
    p.heading("5. Settlement ledger (double entry)");
    p.mono(sprintf("%-22s %-28s %16s  %s", "Debit", "Credit", "Amount", "Reference"));
    for (LedgerRow l : ledger == null ? new ArrayList<LedgerRow>() : ledger) {
      p.mono(sprintf("%-22s %-28s %16s  %s", l.debit, l.credit, usd(l.amount), l.ref));
    }
    p.heading("Attestation");
    p.text("This report was generated automatically at the end of the capital call process. Every step (notice,");
    p.text("follow-ups, risk scoring, bridge financing, GP decisions, settlement) is auditable in Camunda Operate.");

    return p.bytes();
  }
}
