package com.shinro.migration.cmnassetmgmt.activities;

import com.shinro.migration.cmnassetmgmt.domain.Models.FollowUpInput;
import com.shinro.migration.cmnassetmgmt.domain.Models.LPResponse;
import com.shinro.migration.cmnassetmgmt.domain.Models.RecordInput;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** One activity per Camunda job worker of the lp-response process. */
@ActivityInterface
public interface LpResponseActivities {
  /** Sends the tiered follow-up. Returns the stage, which the process keeps as followUps. */
  @ActivityMethod
  int autoFollowUp(FollowUpInput in);

  /** Finalises one LP as committed, deferred or defaulted. */
  @ActivityMethod
  LPResponse recordLPResponse(RecordInput in);
}
