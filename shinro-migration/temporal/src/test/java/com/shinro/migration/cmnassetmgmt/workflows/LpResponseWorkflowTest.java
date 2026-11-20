package com.shinro.migration.cmnassetmgmt.workflows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shinro.migration.cmnassetmgmt.activities.LpResponseActivities;
import com.shinro.migration.cmnassetmgmt.domain.Models.LP;
import com.shinro.migration.cmnassetmgmt.domain.Models.LPResponse;
import com.shinro.migration.cmnassetmgmt.domain.Models.LpCommitmentSignal;
import com.shinro.migration.cmnassetmgmt.domain.Models.LpRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;

@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LpResponseWorkflowTest {
  private static LpRequest request(String wait) {
    LpRequest r = new LpRequest();
    r.callId = "CC-1";
    r.lp = new LP("LP-1", 1000, "lp1@example.com", 250.0);
    r.waitStage1 = wait;
    r.waitStage2 = wait;
    r.waitStage3 = wait;
    r.waitFinal = wait;
    return r;
  }

  private static LpResponseActivities echo() {
    LpResponseActivities a = mock(LpResponseActivities.class, Mockito.withSettings().withoutAnnotations());
    when(a.autoFollowUp(any())).thenAnswer(i -> ((com.shinro.migration.cmnassetmgmt.domain.Models.FollowUpInput) i.getArgument(0)).stage);
    when(a.recordLPResponse(any())).thenAnswer(i -> {
      com.shinro.migration.cmnassetmgmt.domain.Models.RecordInput in = i.getArgument(0);
      LPResponse r = new LPResponse();
      r.lpId = in.lp.lpId;
      r.status = in.status.equals("committed") && "deferred".equals(in.responseStatus) ? "deferred" : in.status;
      r.amountUSD = in.amountUSD == null ? in.lp.drawAmountUSD : in.amountUSD;
      r.followUps = in.followUps;
      return r;
    });
    return a;
  }

  private static LPResponse run(String wait, LpCommitmentSignal sig) {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    try {
      Worker w = env.newWorker("lp-response");
      w.registerWorkflowImplementationTypes(LpResponseWorkflowImpl.class);
      w.registerActivitiesImplementations(echo());
      env.start();
      WorkflowClient client = env.getWorkflowClient();
      LpResponseWorkflow wf = client.newWorkflowStub(LpResponseWorkflow.class,
          WorkflowOptions.newBuilder().setTaskQueue(w.getTaskQueue()).build());
      WorkflowClient.start(wf::run, request(wait));
      if (sig != null) {
        wf.lpCommitment(sig);
      }
      return WorkflowStub.fromTyped(wf).getResult(LPResponse.class);
    } finally {
      env.close();
    }
  }

  @Test
  void commitmentRecordsCommittedWithDrawAmount() {
    LPResponse r = run("PT1H", new LpCommitmentSignal("LP-1", "committed", null));
    assertEquals("committed", r.status);
    assertEquals(250.0, r.amountUSD);
  }

  @Test
  void commitmentWithAmountUsesIt() {
    LPResponse r = run("PT1H", new LpCommitmentSignal("LP-1", "committed", 100.0));
    assertEquals("committed", r.status);
    assertEquals(100.0, r.amountUSD);
  }

  @Test
  void deferredCommitment() {
    LPResponse r = run("PT1H", new LpCommitmentSignal("LP-1", "deferred", null));
    assertEquals("deferred", r.status);
  }

  @Test
  void noResponseDefaultsAfterThreeFollowUps() {
    LPResponse r = run("PT5S", null);
    assertEquals("defaulted", r.status);
    assertEquals(3, r.followUps);
  }
}
