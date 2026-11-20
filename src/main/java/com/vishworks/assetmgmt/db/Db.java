package com.vishworks.assetmgmt.db;

import com.vishworks.assetmgmt.util.Errors;
import com.vishworks.assetmgmt.util.Log;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Postgres access over a Hikari pool.
 *
 * <p>SQL is written with numbered {@code $n} placeholders; {@link #prepare} rewrites them to
 * JDBC {@code ?} placeholders and binds the arguments in occurrence order, so a placeholder
 * used twice is bound twice and each statement names its parameters explicitly.
 */
public final class Db implements AutoCloseable {
  private final HikariDataSource ds;

  private Db(HikariDataSource ds) {
    this.ds = ds;
  }

  /** Opens a pool and waits (up to ~2 min) for Postgres to accept connections (db.Connect). */
  public static Db connect(String url) throws Exception {
    HikariConfig cfg = toHikari(url);
    cfg.setInitializationFailTimeout(-1); // do not fail on construction; we ping ourselves below
    cfg.setPoolName("assetmgmt");
    // Pool size: one per core, with a floor of 4.
    cfg.setMaximumPoolSize(Math.max(4, Runtime.getRuntime().availableProcessors()));
    Exception err = null;
    for (int i = 0; i < 40; i++) {
      // A direct connection attempt, which fails fast while Postgres is still starting.
      try (Connection c = java.sql.DriverManager.getConnection(cfg.getJdbcUrl(), cfg.getUsername(), cfg.getPassword())) {
        if (c.isValid(5)) {
          return new Db(new HikariDataSource(cfg));
        }
      } catch (Exception e) {
        err = e;
      }
      Log.printf("waiting for postgres: %v", err == null ? "connection not valid" : Errors.message(err));
      Thread.sleep(3000);
    }
    throw err != null ? err : new SQLException("postgres not reachable");
  }

  /**
   * Converts a postgres://user:pass@host:port/db?sslmode=disable connection URL
   * into a Hikari/JDBC configuration. jdbc:postgresql:// URLs are accepted as-is.
   */
  static HikariConfig toHikari(String url) {
    HikariConfig cfg = new HikariConfig();
    if (url.startsWith("jdbc:")) {
      cfg.setJdbcUrl(url);
      return cfg;
    }
    URI u = URI.create(url);
    String host = u.getHost() == null ? "localhost" : u.getHost();
    int port = u.getPort() < 0 ? 5432 : u.getPort();
    String path = u.getRawPath() == null ? "" : u.getRawPath();
    String db = path.startsWith("/") ? path.substring(1) : path;
    StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(host).append(':').append(port).append('/')
        .append(db);
    List<String> params = new ArrayList<>();
    if (u.getRawQuery() != null) {
      for (String kv : u.getRawQuery().split("&")) {
        if (kv.isEmpty()) continue;
        int eq = kv.indexOf('=');
        String k = dec(eq < 0 ? kv : kv.substring(0, eq));
        String v = eq < 0 ? "" : dec(kv.substring(eq + 1));
        if (k.equals("sslmode")) {
          // libpq "disable" == pgjdbc "disable"; other values have the same names in pgjdbc.
          params.add("sslmode=" + v);
        } else {
          params.add(k + "=" + v);
        }
      }
    }
    if (!params.isEmpty()) {
      jdbc.append('?').append(String.join("&", params));
    }
    cfg.setJdbcUrl(jdbc.toString());
    if (u.getRawUserInfo() != null) {
      String ui = u.getRawUserInfo();
      int c = ui.indexOf(':');
      cfg.setUsername(dec(c < 0 ? ui : ui.substring(0, c)));
      if (c >= 0) cfg.setPassword(dec(ui.substring(c + 1)));
    }
    return cfg;
  }

  private static String dec(String s) {
    return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
  }

  public Connection getConnection() throws SQLException {
    return ds.getConnection();
  }

  @Override
  public void close() {
    ds.close();
  }

  // ─── statement helpers ──────────────────────────────────────────────────

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$(\\d+)");

  /** Rewrites $n placeholders to ? and binds each argument at every position it appears. */
  public static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
    Matcher m = PLACEHOLDER.matcher(sql);
    StringBuilder out = new StringBuilder();
    List<Integer> order = new ArrayList<>();
    while (m.find()) {
      order.add(Integer.parseInt(m.group(1)));
      m.appendReplacement(out, "?");
    }
    m.appendTail(out);
    PreparedStatement ps = c.prepareStatement(out.toString());
    for (int i = 0; i < order.size(); i++) {
      bind(c, ps, i + 1, args[order.get(i) - 1]);
    }
    return ps;
  }

  private static void bind(Connection c, PreparedStatement ps, int idx, Object v) throws SQLException {
    if (v == null) {
      ps.setNull(idx, Types.NULL);
    } else if (v instanceof Double d) {
      if (Double.isFinite(d)) {
        // Send NUMERIC as the shortest exact decimal, so a monetary value is not widened
        // to a binary-float approximation in the column.
        ps.setBigDecimal(idx, new BigDecimal(Double.toString(d)));
      } else {
        ps.setDouble(idx, d);
      }
    } else if (v instanceof Integer n) {
      ps.setInt(idx, n);
    } else if (v instanceof Long n) {
      ps.setLong(idx, n);
    } else if (v instanceof Boolean b) {
      ps.setBoolean(idx, b);
    } else if (v instanceof LocalDateTime t) {
      ps.setObject(idx, t);
    } else if (v instanceof String[] arr) {
      Array a = c.createArrayOf("text", arr);
      ps.setArray(idx, a);
    } else {
      ps.setString(idx, v.toString());
    }
  }

  /** pool.Exec / tx.Exec. */
  public static int exec(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement ps = prepare(c, sql, args)) {
      return ps.executeUpdate();
    }
  }

  /** pool.Exec using a pooled connection. */
  public int exec(String sql, Object... args) throws SQLException {
    try (Connection c = getConnection()) {
      return exec(c, sql, args);
    }
  }

  /** Row callback used by {@link #query}. */
  @FunctionalInterface
  public interface RowReader<T> {
    T read(ResultSet rs) throws SQLException;
  }

  /** pool.Query + rows.Scan. */
  public <T> List<T> query(String sql, RowReader<T> reader, Object... args) throws SQLException {
    try (Connection c = getConnection(); PreparedStatement ps = prepare(c, sql, args); ResultSet rs = ps.executeQuery()) {
      List<T> out = new ArrayList<>();
      while (rs.next()) {
        out.add(reader.read(rs));
      }
      return out;
    }
  }

  /**
   * Generic query for the dashboard endpoints: every row becomes a map keyed by column name
   * (int4→Integer, int8→Long, float8→Double, bool→Boolean, varchar/text→String,
   * timestamp→LocalDateTime, NULL→null). Keys are sorted so the JSON field order is stable
   * from one response to the next.
   */
  public List<Map<String, Object>> rowsToMaps(String sql, Object... args) throws SQLException {
    try (Connection c = getConnection(); PreparedStatement ps = prepare(c, sql, args); ResultSet rs = ps.executeQuery()) {
      ResultSetMetaData md = rs.getMetaData();
      List<Map<String, Object>> out = new ArrayList<>();
      while (rs.next()) {
        Map<String, Object> m = new TreeMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
          m.put(md.getColumnLabel(i), value(rs, i, md.getColumnTypeName(i)));
        }
        out.add(m);
      }
      return out;
    }
  }

  private static Object value(ResultSet rs, int i, String type) throws SQLException {
    Object v;
    switch (type) {
      case "int2", "int4" -> v = rs.getInt(i);
      case "int8" -> v = rs.getLong(i);
      case "float4", "float8" -> v = rs.getDouble(i);
      case "bool" -> v = rs.getBoolean(i);
      case "timestamp" -> v = rs.getObject(i, LocalDateTime.class);
      case "timestamptz" -> v = rs.getObject(i, java.time.OffsetDateTime.class);
      case "numeric" -> v = rs.getBigDecimal(i);
      default -> v = rs.getString(i);
    }
    return rs.wasNull() ? null : v;
  }

  /** Convenience for building ordered maps in handlers. */
  public static Map<String, Object> map() {
    return new LinkedHashMap<>();
  }
}
