package com.shinro.migration.cmnassetmgmt.activities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shinro.migration.cmnassetmgmt.domain.Models.CapitalCallInput;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestActivityEnvironment;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class CapitalCallActivitiesTest {
  @Test
  void invalidInputFailsNonRetryable() {
    TestActivityEnvironment env = TestActivityEnvironment.newInstance();
    try {
      env.registerActivitiesImplementations(new CapitalCallActivitiesImpl(new Support()));
      CapitalCallActivities a = env.newActivityStub(CapitalCallActivities.class,
          io.temporal.activity.ActivityOptions.newBuilder().setScheduleToCloseTimeout(java.time.Duration.ofSeconds(10)).build());
      CapitalCallInput in = new CapitalCallInput();
      ActivityFailure f = assertThrows(ActivityFailure.class, () -> a.issueCapitalCall(in));
      ApplicationFailure af = assertInstanceOf(ApplicationFailure.class, f.getCause());
      assertEquals("InvalidInput", af.getType());
      assertTrue(af.isNonRetryable());
    } finally {
      env.close();
    }
  }
}
