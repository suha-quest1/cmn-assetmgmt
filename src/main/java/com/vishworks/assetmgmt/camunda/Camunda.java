package com.vishworks.assetmgmt.camunda;

import com.vishworks.assetmgmt.json.JsonCodec;
import com.vishworks.assetmgmt.util.Errors;
import io.camunda.client.CamundaClient;
import io.camunda.client.CamundaClientBuilder;
import io.camunda.client.CredentialsProvider;
import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ClientHttpException;
import io.camunda.client.api.command.DeployResourceCommandStep1;
import io.camunda.client.api.command.ProblemException;
import io.camunda.client.api.response.DeploymentEvent;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Creation and configuration of the official {@link CamundaClient}.
 *
 * <p>Configured with {@code preferRestOverGrpc(true)} so every command (deployment, process
 * instance creation, message publication/correlation, job activation/completion/failure,
 * topology) goes over the Orchestration Cluster REST API (/v2). That keeps the deployment to
 * a single endpoint — only {@code CAMUNDA_REST_ADDRESS} is needed, with no separate gRPC
 * address or port to open.
 */
public final class Camunda {
  private Camunda() {}

  /** HTTP timeout for every command. Long enough to cover a cold cluster accepting a deployment. */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

  /**
   * Builds a client for the given REST base URL. Basic authentication is applied when
   * CAMUNDA_USERNAME is set (CAMUNDA_PASSWORD may be empty), as in camunda.New().
   */
  public static CamundaClient newClient(String baseUrl) {
    String rest = baseUrl.replaceAll("/+$", "");
    CamundaClientBuilder b = CamundaClient.newClientBuilder()
        // Configuration comes only from this application's own environment variables,
        // so the client's CAMUNDA_* auto-configuration cannot change behaviour.
        .applyEnvironmentVariableOverrides(false)
        .restAddress(URI.create(rest))
        .preferRestOverGrpc(true)
        .defaultRequestTimeout(REQUEST_TIMEOUT)
        // One virtual thread per activated job, so a slow handler cannot stall the others.
        .jobHandlingExecutor(Executors.newVirtualThreadPerTaskExecutor(), true);
    String user = System.getenv("CAMUNDA_USERNAME");
    if (user != null && !user.isEmpty()) {
      String pw = System.getenv("CAMUNDA_PASSWORD");
      CredentialsProvider cp = CredentialsProvider.newBasicAuthCredentialsProviderBuilder()
          .applyEnvironmentOverrides(false)
          .username(user)
          .password(pw == null ? "" : pw)
          .build();
      b.credentialsProvider(cp);
    }
    return b.build();
  }

  /** Blocks until the cluster answers the topology endpoint (WaitReady). */
  public static void waitReady(CamundaClient client) throws InterruptedException {
    while (true) {
      try {
        client.newTopologyRequest().send().join();
        return;
      } catch (RuntimeException e) {
        Thread.sleep(3000);
      }
    }
  }

  /** Uploads BPMN / DMN / form files in a single deployment (Deploy). */
  public static DeploymentEvent deploy(CamundaClient client, List<Path> files) throws Exception {
    if (files.isEmpty()) {
      throw new IllegalArgumentException("no resources to deploy");
    }
    DeployResourceCommandStep1 step = client.newDeployResourceCommand();
    DeployResourceCommandStep1.DeployResourceCommandStep2 cmd = null;
    for (Path f : files) {
      byte[] data = Files.readAllBytes(f);
      String name = f.getFileName().toString();
      cmd = cmd == null ? step.addResourceBytes(data, name) : cmd.addResourceBytes(data, name);
    }
    return cmd.send().join();
  }

  // ─── errors ───────────────────────────────────────────────────────────

  /** HTTP status of a failed Camunda API call, or -1 when it did not get an HTTP response. */
  public static int status(Throwable t) {
    ClientHttpException he = Errors.find(t, ClientHttpException.class);
    return he == null ? -1 : he.code();
  }

  /** IsNotFound: the API answered 404 (e.g. no message subscription to correlate with). */
  public static boolean isNotFound(Throwable t) {
    return status(t) == 404;
  }

  /**
   * Error text as "camunda api: HTTP 404: {body}". For RFC 9457 problem responses the body is
   * re-serialised from the parsed problem detail in the field order Camunda uses (type, title,
   * status, detail, instance), so the message reads the same as the response that produced it.
   */
  public static String message(Throwable t) {
    ProblemException pe = Errors.find(t, ProblemException.class);
    if (pe != null) {
      ProblemDetail d = pe.details();
      Map<String, Object> body = new LinkedHashMap<>();
      if (d != null) {
        if (d.getType() != null) body.put("type", d.getType().toString());
        if (d.getTitle() != null) body.put("title", d.getTitle());
        if (d.getStatus() != null) body.put("status", d.getStatus());
        if (d.getDetail() != null) body.put("detail", d.getDetail());
        if (d.getInstance() != null) body.put("instance", d.getInstance().toString());
      }
      return "camunda api: HTTP " + pe.code() + ": " + rawJson(body);
    }
    ClientHttpException he = Errors.find(t, ClientHttpException.class);
    if (he != null) {
      return "camunda api: HTTP " + he.code() + ": " + (he.reason() == null ? "" : he.reason());
    }
    return Errors.message(t);
  }

  /**
   * Compact JSON with plain Jackson escaping, deliberately not {@code JsonCodec}: this is an
   * error body echoed into a message, so '&lt;' and '&amp;' should stay readable rather than
   * being escaped.
   */
  private static String rawJson(Map<String, Object> body) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
    } catch (Exception e) {
      return JsonCodec.toJson(body);
    }
  }
}
