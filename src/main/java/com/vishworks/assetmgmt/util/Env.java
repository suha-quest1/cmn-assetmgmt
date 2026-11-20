package com.vishworks.assetmgmt.util;

/** Environment lookup in which an empty value counts as unset, so a blank var falls back to the default. */
public final class Env {
  private Env() {}

  public static String get(String key, String def) {
    String v = System.getenv(key);
    return (v != null && !v.isEmpty()) ? v : def;
  }
}
