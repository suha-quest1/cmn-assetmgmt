package com.shinro.migration.cmnassetmgmt.activities;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Postgres access. SQL uses numbered {@code $n} placeholders. They are rewritten to JDBC
 * {@code ?} and bound in order of occurrence, as in the source application. Connections are
 * opened lazily, so the worker can start before Postgres is up.
 */
public class Db {
  private final String jdbcUrl;
  private final String user;
  private final String password;

  /** Accepts postgres://user:pass@host:port/db?sslmode=disable or a jdbc:postgresql:// URL. */
  public Db(String url) {
    if (url.startsWith("jdbc:")) {
      this.jdbcUrl = url;
      this.user = null;
      this.password = null;
      return;
    }
    URI u = URI.create(url);
    String host = u.getHost() == null ? "localhost" : u.getHost();
    int port = u.getPort() < 0 ? 5432 : u.getPort();
    String path = u.getRawPath() == null ? "" : u.getRawPath();
    String db = path.startsWith("/") ? path.substring(1) : path;
    StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(host).append(':').append(port).append('/').append(db);
    if (u.getRawQuery() != null && !u.getRawQuery().isEmpty()) {
      jdbc.append('?').append(u.getRawQuery());
    }
    this.jdbcUrl = jdbc.toString();
    String ui = u.getRawUserInfo();
    if (ui != null) {
      int c = ui.indexOf(':');
      this.user = dec(c < 0 ? ui : ui.substring(0, c));
      this.password = c < 0 ? null : dec(ui.substring(c + 1));
    } else {
      this.user = null;
      this.password = null;
    }
  }

  private static String dec(String s) {
    return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
  }

  public Connection getConnection() throws SQLException {
    if (user == null) {
      return DriverManager.getConnection(jdbcUrl);
    }
    return DriverManager.getConnection(jdbcUrl, user, password);
  }

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$(\\d+)");

  static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
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
      bind(ps, i + 1, args[order.get(i) - 1]);
    }
    return ps;
  }

  private static void bind(PreparedStatement ps, int idx, Object v) throws SQLException {
    if (v == null) {
      ps.setNull(idx, Types.NULL);
    } else if (v instanceof Double d) {
      if (Double.isFinite(d)) {
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
    } else {
      ps.setString(idx, v.toString());
    }
  }

  public static int exec(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement ps = prepare(c, sql, args)) {
      return ps.executeUpdate();
    }
  }

  public int exec(String sql, Object... args) throws SQLException {
    try (Connection c = getConnection()) {
      return exec(c, sql, args);
    }
  }

  /** Statement runner inside a transaction. */
  public interface Tx {
    int exec(String sql, Object... args) throws SQLException;
  }

  /** Body of a transaction. */
  @FunctionalInterface
  public interface TxBody {
    void run(Tx tx) throws Exception;
  }

  /** Runs the body in one transaction: commit on success, rollback on any failure. */
  public void transaction(TxBody body) throws Exception {
    try (Connection c = getConnection()) {
      c.setAutoCommit(false);
      try {
        body.run((sql, args) -> exec(c, sql, args));
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    }
  }

  /** Row callback used by {@link #query}. */
  @FunctionalInterface
  public interface RowReader<T> {
    T read(ResultSet rs) throws SQLException;
  }

  public <T> List<T> query(String sql, RowReader<T> reader, Object... args) throws SQLException {
    try (Connection c = getConnection(); PreparedStatement ps = prepare(c, sql, args); ResultSet rs = ps.executeQuery()) {
      List<T> out = new ArrayList<>();
      while (rs.next()) {
        out.add(reader.read(rs));
      }
      return out;
    }
  }
}
