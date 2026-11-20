package com.vishworks.assetmgmt.camunda;

import com.vishworks.assetmgmt.json.JsonCodec;
import com.vishworks.assetmgmt.util.Errors;
import com.vishworks.assetmgmt.util.Log;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobWorker;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers one Camunda job worker per job type.
 *
 * <p>Polling and failure handling:
 *
 * <ul>
 *   <li>REST long polling with a 20 s request timeout, up to 16 jobs per type, 60 s job lock;
 *   <li>200 ms pause after an empty poll, 2 s pause after a failed poll;
 *   <li>each activated job is handled concurrently (virtual thread per job);
 *   <li>a handler error fails the job with {@code retries - 1} (never below 0) and a 5 s
 *       retry back-off, so Camunda raises an incident in Operate once retries are exhausted;
 *   <li>an unexpected runtime exception is caught and reported as "panic: …", so it shows up
 *       as an incident instead of killing the worker;
 *   <li>a successful handler completes the job with the variables it returned.
 * </ul>
 */
public final class JobWorkers {

  /**
   * Executes a job and returns the variables to complete it with (may be null). Throwing
   * fails the job.
   */
  @FunctionalInterface
  public interface Handler {
    Map<String, Object> handle(Job job) throws Exception;
  }

  /** A handler failure that is expected and already has a usable message. */
  public static class JobException extends Exception {
    public JobException(String message) {
      super(message);
    }

    public JobException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** An activated job, wrapped so handlers get typed variable access without touching Jackson. */
  public static final class Job {
    private final ActivatedJob job;

    public Job(ActivatedJob job) {
      this.job = job;
    }

    public long key() {
      return job.getKey();
    }

    public String type() {
      return job.getType();
    }

    public String elementId() {
      return job.getElementId();
    }

    public int retries() {
      return job.getRetries();
    }

    /** Process instance key as a string (the v2 API represents keys as strings). */
    public String processInstanceKey() {
      return Long.toString(job.getProcessInstanceKey());
    }

    /** Decodes the job variables into a new value of the given type. */
    public <T> T bind(Class<T> type) throws IOException {
      String vars = job.getVariables();
      if (vars == null || vars.isEmpty()) {
        vars = "{}";
      }
      return JsonCodec.fromJson(vars, type);
    }

    /** Returns a custom header as string ("" when absent). */
    public String header(String name) {
      Map<String, String> h = job.getCustomHeaders();
      String v = h == null ? null : h.get(name);
      return v == null ? "" : v;
    }
  }

  private final CamundaClient client;
  private final String name;
  private final Map<String, Handler> handlers = new LinkedHashMap<>();
  private final List<JobWorker> open = new ArrayList<>();

  /** Number of jobs activated per poll per job type. */
  public int maxJobs = 16;
  /** How long an activated job is locked to this worker. */
  public Duration lockTimeout = Duration.ofSeconds(60);

  public JobWorkers(CamundaClient client, String name) {
    this.client = client;
    this.name = name;
  }

  public void handle(String jobType, Handler h) {
    handlers.put(jobType, h);
  }

  /** Opens one polling job worker per registered job type. */
  public synchronized void start() {
    for (Map.Entry<String, Handler> e : handlers.entrySet()) {
      String jobType = e.getKey();
      Handler h = e.getValue();
      JobWorker w = client.newWorker()
          .jobType(jobType)
          .handler((jobClient, job) -> execute(jobClient, job, h))
          .name(name)
          .timeout(lockTimeout)
          .maxJobsActive(maxJobs)
          .requestTimeout(Duration.ofSeconds(20))
          .pollInterval(Duration.ofMillis(200))
          .backoffSupplier(previous -> 2000L)
          .streamEnabled(false)
          .open();
      open.add(w);
      Log.printf("[worker] subscribed to job type %q", jobType);
    }
  }

  /** Stops polling for new jobs. */
  public synchronized void close() {
    for (JobWorker w : open) {
      try {
        w.close();
      } catch (RuntimeException ignored) {
        // shutting down
      }
    }
    open.clear();
  }

  void execute(JobClient jobClient, ActivatedJob activated, Handler h) {
    Job job = new Job(activated);
    Map<String, Object> vars;
    try {
      vars = h.handle(job);
    } catch (Throwable t) {
      String msg = errorText(t);
      int retries = Math.max(0, job.retries() - 1);
      Log.printf("[worker] job %s (%s/%s) failed, retries left %d: %v", Long.toString(job.key()), job.type(),
          job.elementId(), retries, msg);
      try {
        jobClient.newFailCommand(job.key())
            .retries(retries)
            .errorMessage(msg)
            .retryBackoff(Duration.ofSeconds(5))
            .send()
            .join();
      } catch (RuntimeException fe) {
        Log.printf("[worker] fail job %s: %v", Long.toString(job.key()), Camunda.message(fe));
      }
      return;
    }
    try {
      var cmd = jobClient.newCompleteCommand(job.key());
      if (vars != null) {
        cmd = cmd.variables(JsonCodec.toJson(vars));
      }
      cmd.send().join();
    } catch (RuntimeException ce) {
      Log.printf("[worker] complete job %s (%s): %v", Long.toString(job.key()), job.type(), Camunda.message(ce));
      return;
    }
    Log.printf("[worker] completed %-22s element=%-28s pi=%s", job.type(), job.elementId(), job.processInstanceKey());
  }

  /**
   * The {@code errorMessage} reported to Camunda, and therefore the text shown on the incident
   * in Operate: the plain message for an expected failure, prefixed "panic: " for an unchecked
   * one, so the two are distinguishable at a glance.
   */
  static String errorText(Throwable t) {
    t = Errors.unwrap(t);
    if (t instanceof RuntimeException || t instanceof Error) {
      return "panic: " + Errors.message(t);
    }
    return Errors.message(t);
  }
}
