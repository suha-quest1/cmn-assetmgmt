package com.shinro.migration.cmnassetmgmt.activities;

import static com.shinro.migration.cmnassetmgmt.activities.Fmt.sprintf;
import static com.shinro.migration.cmnassetmgmt.activities.Support.m;

import com.shinro.migration.cmnassetmgmt.domain.Models.FollowUpInput;
import com.shinro.migration.cmnassetmgmt.domain.Models.LPResponse;
import com.shinro.migration.cmnassetmgmt.domain.Models.RecordInput;
import java.sql.SQLException;
import java.util.Map;

/** Activities of the lp-response process (translation of Handlers.autoFollowUp / recordLPResponse). */
public class LpResponseActivitiesImpl implements LpResponseActivities {
  static final Map<Integer, String> STAGE_NAMES = Map.of(
      1, "email reminder (D+3)", 2, "phone call (D+7)", 3, "legal notice (D+10)");

  private final Support s;

  public LpResponseActivitiesImpl(Support s) {
    this.s = s;
  }

  @Override
  public int autoFollowUp(FollowUpInput in) {
    int stage = in.stage;
    String name = STAGE_NAMES.getOrDefault(stage, "");
    // Idempotency key callId:lpId:stage. SES must dedupe on it for retries to be safe.
    s.email(m(
        "to", in.lp.email,
        "subject", sprintf("Capital Call %s — Follow-up %d: %s", in.callId, stage, name),
        "body", sprintf("LP %s has not confirmed its $%.2f draw. Escalation stage %d (%s).", in.lp.lpId, in.lp.drawAmountUSD, stage, name),
        "callId", in.callId, "lpId", in.lp.lpId, "stage", stage, "type", "followup"),
        in.callId + ":" + in.lp.lpId + ":" + stage);
    try {
      s.db.exec("UPDATE capital_call_lps SET status='overdue', followup_stage=$3, updated_at=NOW() WHERE call_id=$1 AND lp_id=$2",
          in.callId, in.lp.lpId, stage);
    } catch (SQLException e) {
      throw CapitalCallActivitiesImpl.fail(e.getMessage(), e);
    }
    s.event(in.callId, in.lp.lpId, "autoFollowUp", "Sent " + name);
    return stage;
  }

  @Override
  public LPResponse recordLPResponse(RecordInput in) {
    String status = in.status == null ? "" : in.status;
    double amount = 0.0;
    if (status.equals("committed")) {
      if ("deferred".equals(in.responseStatus)) {
        status = "deferred";
      } else {
        amount = in.lp.drawAmountUSD;
        if (in.amountUSD != null && in.amountUSD > 0) {
          amount = in.amountUSD;
        }
      }
    }
    if (status.isEmpty()) {
      status = "defaulted";
    }
    try {
      // Idempotency key callId:lpId:followUps. The UPDATEs set absolute values, so a retry is safe.
      s.db.exec("UPDATE capital_call_lps SET status=$3, committed_usd=$4, updated_at=NOW() WHERE call_id=$1 AND lp_id=$2",
          in.callId, in.lp.lpId, status, amount);
      s.db.exec("UPDATE capital_calls SET received_amount_usd = (SELECT COALESCE(SUM(committed_usd),0) FROM capital_call_lps WHERE call_id=$1 AND status='committed'), updated_at = NOW() WHERE call_id=$1",
          in.callId);
    } catch (SQLException e) {
      throw CapitalCallActivitiesImpl.fail(e.getMessage(), e);
    }
    String detail = sprintf("LP %s -> %s ($%.0f)", in.lp.lpId, status, amount);
    if (in.followUps > 0) {
      detail += sprintf(" after %d follow-up(s)", in.followUps);
    }
    s.event(in.callId, in.lp.lpId, "lpResponse", detail);
    LPResponse r = new LPResponse();
    r.lpId = in.lp.lpId;
    r.email = in.lp.email;
    r.commitmentUSD = in.lp.commitmentUSD;
    r.drawAmountUSD = in.lp.drawAmountUSD;
    r.status = status;
    r.amountUSD = amount;
    r.followUps = in.followUps;
    return r;
  }
}
