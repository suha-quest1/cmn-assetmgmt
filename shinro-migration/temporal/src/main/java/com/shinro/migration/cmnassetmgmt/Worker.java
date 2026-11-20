package com.shinro.migration.cmnassetmgmt;

import com.shinro.migration.cmnassetmgmt.activities.CapitalCallActivitiesImpl;
import com.shinro.migration.cmnassetmgmt.activities.LpResponseActivitiesImpl;
import com.shinro.migration.cmnassetmgmt.activities.Support;
import com.shinro.migration.cmnassetmgmt.domain.Models;
import com.shinro.migration.cmnassetmgmt.workflows.CapitalCallWorkflowImpl;
import com.shinro.migration.cmnassetmgmt.workflows.LpResponseWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;

/** Entry point: starts one worker per process. The task queue is the process id. */
public final class Worker {
  private Worker() {}

  public static void main(String[] args) {
    String target = System.getenv("TEMPORAL_ADDRESS");
    if (target == null || target.isEmpty()) {
      target = "127.0.0.1:7233";
    }
    WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
        WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
    WorkflowClient client = WorkflowClient.newInstance(service);
    WorkerFactory factory = WorkerFactory.newInstance(client);
    Support support = Support.fromEnv();

    io.temporal.worker.Worker capitalCall = factory.newWorker(Models.PROCESS_CAPITAL_CALL);
    capitalCall.registerWorkflowImplementationTypes(CapitalCallWorkflowImpl.class);
    capitalCall.registerActivitiesImplementations(new CapitalCallActivitiesImpl(support));

    io.temporal.worker.Worker lpResponse = factory.newWorker(Models.PROCESS_LP_RESPONSE);
    lpResponse.registerWorkflowImplementationTypes(LpResponseWorkflowImpl.class);
    lpResponse.registerActivitiesImplementations(new LpResponseActivitiesImpl(support));

    factory.start();
  }
}
