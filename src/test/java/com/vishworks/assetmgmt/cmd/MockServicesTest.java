package com.vishworks.assetmgmt.cmd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.vishworks.assetmgmt.http.Http;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * The HTTP contract of the mock services and the router: the status codes, headers and bodies
 * that callers depend on. These are asserted exactly, because the error strings also surface
 * in job incidents.
 */
class MockServicesTest {
  static final HttpClient C = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  static HttpResponse<String> call(int port, String method, String path, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return C.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void mockSes() throws Exception {
    MockSesMain.reset();
    HttpServer s = Http.listen("127.0.0.1:0", MockSesMain.routes("<html/>".getBytes()));
    int p = s.getAddress().getPort();
    try {
      assertEquals("[]", call(p, "GET", "/emails", null).body());
      HttpResponse<String> bad = call(p, "POST", "/send", "{bad");
      assertEquals(400, bad.statusCode());
      assertEquals("invalid JSON\n", bad.body());
      assertEquals("nosniff", bad.headers().firstValue("X-Content-Type-Options").orElse(""));
      HttpResponse<String> ok = call(p, "POST", "/send", "{\"type\":\"followup\",\"to\":\"a\",\"stage\":3,\"subject\":\"S <x> & y\"}");
      assertEquals(200, ok.statusCode());
      assertEquals("{\"status\":\"sent\"}\n", ok.body());
      String emails = call(p, "GET", "/emails", null).body();
      assertTrue(emails.matches("\\[\\{\"timestamp\":\"[^\"]+\",\"payload\":\\{\"stage\":3,\"subject\":\"S \\\\u003cx\\\\u003e \\\\u0026 y\",\"to\":\"a\",\"type\":\"followup\"}}]\n"), emails);
      HttpResponse<String> m = call(p, "POST", "/", null);
      assertEquals(405, m.statusCode());
      assertEquals("GET, HEAD", m.headers().firstValue("Allow").orElse(""));
      assertEquals("Method Not Allowed\n", m.body());
      assertEquals("404 page not found\n", call(p, "GET", "/nope", null).body());
      assertEquals(301, call(p, "GET", "/a/../emails", null).statusCode());
    } finally {
      s.stop(0);
    }
  }

  @Test
  void mockCreditFacility() throws Exception {
    HttpServer s = Http.listen("127.0.0.1:0", MockCreditFacilityMain.routes());
    int p = s.getAddress().getPort();
    try {
      HttpResponse<String> r = call(p, "POST", "/drawdown", "{\"callId\":\"CC-1\",\"amount\":5750000}");
      assertTrue(r.body().matches("\\{\"confirmationId\":\"BRIDGE-CC-1-\\d+\",\"drawdownUSD\":5750000,\"feeUSD\":86250}\n"), r.body());
      HttpResponse<String> g = call(p, "GET", "/drawdown", null);
      assertEquals(405, g.statusCode());
      assertEquals("method not allowed\n", g.body());
      assertEquals(400, call(p, "POST", "/drawdown", "{\"amount\":\"12\"}").statusCode());
      assertEquals("{\"service\":\"mock-credit-facility\",\"status\":\"ok\"}\n", call(p, "DELETE", "/any/path", null).body());
    } finally {
      s.stop(0);
    }
  }
}
