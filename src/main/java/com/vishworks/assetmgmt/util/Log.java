package com.vishworks.assetmgmt.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application logging. The layout (date, time, message, on stderr) is configured in
 * log4j2.xml; format strings go through {@link Format#sprintf} so numbers and durations in
 * log lines are formatted like everywhere else.
 */
public final class Log {
  private static final Logger LOG = LoggerFactory.getLogger("app");

  private Log() {}

  public static void printf(String format, Object... args) {
    LOG.info(Format.sprintf(format, args));
  }

  /** Logs the error and exits with status 1, flushing the log first. */
  public static void fatal(Object err) {
    LOG.error(Format.v(err instanceof Throwable t ? Errors.message(t) : err));
    org.apache.logging.log4j.LogManager.shutdown();
    Runtime.getRuntime().halt(1);
  }

  public static void fatalf(String format, Object... args) {
    fatal(Format.sprintf(format, args));
  }
}
