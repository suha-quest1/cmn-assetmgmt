package com.shinro.migration.cmnassetmgmt.activities;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Text formatting helpers ported from the source application. They keep amounts formatted
 * the same way in events, e-mails, the JSON report and the PDF (%f rounds half-to-even on
 * the exact binary value, never in exponent notation).
 */
final class Fmt {
  private Fmt() {}

  /** Rounds half away from zero, on the exact binary value. */
  static double round(double x) {
    if (Double.isNaN(x) || Double.isInfinite(x)) {
      return x;
    }
    double r = new BigDecimal(x).setScale(0, RoundingMode.HALF_UP).doubleValue();
    return (r == 0 && (x < 0 || 1 / x < 0)) ? -0.0 : r;
  }

  /** Rounds a monetary amount to cents. */
  static double round2(double f) {
    return round(f * 100) / 100;
  }

  /** Fixed-point with prec decimals, rounded half-to-even on the exact binary value. */
  static String fixed(double x, int prec) {
    if (Double.isNaN(x)) return "NaN";
    if (Double.isInfinite(x)) return x > 0 ? "+Inf" : "-Inf";
    String s = new BigDecimal(x).setScale(prec, RoundingMode.HALF_EVEN).toPlainString();
    boolean neg = x < 0 || (x == 0 && 1 / x < 0);
    if (neg && !s.startsWith("-")) s = "-" + s;
    return s;
  }

  /** Dollar format with thousands separators, e.g. $1,234,567.89. */
  static String usd(double f) {
    String s = fixed(f, 2);
    String intPart = s.substring(0, s.length() - 3), dec = s.substring(s.length() - 3);
    String neg = "";
    if (!intPart.isEmpty() && intPart.charAt(0) == '-') {
      neg = "-";
      intPart = intPart.substring(1);
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < intPart.length(); i++) {
      if (i > 0 && (intPart.length() - i) % 3 == 0) {
        out.append(',');
      }
      out.append(intPart.charAt(i));
    }
    return neg + "$" + out + dec;
  }

  private static String shortestPlain(double x) {
    if (x == 0) return (1 / x < 0) ? "-0" : "0";
    return new BigDecimal(Double.toString(x)).stripTrailingZeros().toPlainString();
  }

  private static String shortestExp(double x) {
    if (x == 0) return ((1 / x < 0) ? "-" : "") + "0e+00";
    BigDecimal bd = new BigDecimal(Double.toString(x)).stripTrailingZeros();
    String digits = bd.unscaledValue().abs().toString();
    int exp = digits.length() - 1 - bd.scale();
    StringBuilder sb = new StringBuilder();
    if (bd.signum() < 0) sb.append('-');
    sb.append(digits.charAt(0));
    if (digits.length() > 1) sb.append('.').append(digits, 1, digits.length());
    sb.append('e').append(exp < 0 ? '-' : '+');
    int a = Math.abs(exp);
    if (a < 10) sb.append('0');
    sb.append(a);
    return sb.toString();
  }

  private static String special(double f) {
    return Double.isNaN(f) ? "NaN" : (f > 0 ? "+Inf" : "-Inf");
  }

  private static String gFloat(double x) {
    if (Double.isNaN(x) || Double.isInfinite(x)) return special(x);
    if (x == 0) return (1 / x < 0) ? "-0" : "0";
    BigDecimal bd = new BigDecimal(Double.toString(x)).stripTrailingZeros();
    String digits = bd.unscaledValue().abs().toString();
    int exp = digits.length() - 1 - bd.scale();
    if (exp < -4 || exp >= 21) {
      return shortestExp(x);
    }
    return shortestPlain(x);
  }

  /** Interpolates verbs %s %v %d %f %%, the - 0 + flags, width and precision. */
  static String sprintf(String format, Object... args) {
    StringBuilder out = new StringBuilder();
    int argi = 0;
    int n = format.length();
    for (int i = 0; i < n; i++) {
      char c = format.charAt(i);
      if (c != '%') {
        out.append(c);
        continue;
      }
      i++;
      if (i >= n) {
        out.append("%!(NOVERB)");
        break;
      }
      boolean minus = false, zero = false, plus = false;
      for (; i < n; i++) {
        char f = format.charAt(i);
        if (f == '-') minus = true;
        else if (f == '0') zero = !minus;
        else if (f == '+') plus = true;
        else break;
      }
      int width = -1;
      int start = i;
      while (i < n && Character.isDigit(format.charAt(i))) i++;
      if (i > start) width = Integer.parseInt(format.substring(start, i));
      int prec = -1;
      if (i < n && format.charAt(i) == '.') {
        i++;
        start = i;
        while (i < n && Character.isDigit(format.charAt(i))) i++;
        prec = i > start ? Integer.parseInt(format.substring(start, i)) : 0;
      }
      if (i >= n) {
        out.append("%!(NOVERB)");
        break;
      }
      char verb = format.charAt(i);
      if (verb == '%') {
        out.append('%');
        continue;
      }
      if (argi >= args.length) {
        out.append("%!").append(verb).append("(MISSING)");
        continue;
      }
      Object arg = args[argi++];
      String s;
      boolean numeric = false;
      switch (verb) {
        case 'd' -> {
          numeric = true;
          s = (arg instanceof Number num) ? Long.toString(num.longValue()) : "%!d(" + v(arg) + ")";
          if (plus && arg instanceof Number num && num.longValue() >= 0) s = "+" + s;
        }
        case 'f' -> {
          numeric = true;
          s = (arg instanceof Number num) ? fixed(num.doubleValue(), prec < 0 ? 6 : prec) : "%!f(" + v(arg) + ")";
          if (plus && !s.startsWith("-")) s = "+" + s;
        }
        case 's', 'v' -> {
          s = v(arg);
          if (prec >= 0 && verb == 's' && s.length() > prec) s = s.substring(0, prec);
        }
        default -> s = "%!" + verb + "(" + v(arg) + ")";
      }
      out.append(pad(s, width, minus, zero && numeric));
    }
    return out.toString();
  }

  private static String pad(String s, int width, boolean left, boolean zero) {
    int len = s.codePointCount(0, s.length());
    if (width < 0 || len >= width) return s;
    int fill = width - len;
    if (left) {
      return s + " ".repeat(fill);
    }
    if (zero) {
      if (s.startsWith("-") || s.startsWith("+")) {
        return s.charAt(0) + "0".repeat(fill) + s.substring(1);
      }
      return "0".repeat(fill) + s;
    }
    return " ".repeat(fill) + s;
  }

  static String v(Object o) {
    if (o == null) return "<nil>";
    if (o instanceof Double d) return gFloat(d);
    if (o instanceof Float f) return gFloat(f);
    if (o instanceof Collection<?> c) {
      StringBuilder sb = new StringBuilder("[");
      boolean first = true;
      for (Object e : c) {
        if (!first) sb.append(' ');
        sb.append(v(e));
        first = false;
      }
      return sb.append(']').toString();
    }
    if (o instanceof Map<?, ?> m) {
      StringBuilder sb = new StringBuilder("map[");
      boolean first = true;
      for (Map.Entry<?, ?> e : new TreeMap<>(m).entrySet()) {
        if (!first) sb.append(' ');
        sb.append(v(e.getKey())).append(':').append(v(e.getValue()));
        first = false;
      }
      return sb.append(']').toString();
    }
    return o.toString();
  }

  static String slice(List<String> l) {
    return v(l == null ? List.of() : l);
  }

  /** RFC 3339 in UTC, to whole seconds. */
  static String rfc3339Utc(Instant t) {
    ZonedDateTime z = t.atZone(ZoneOffset.UTC);
    return String.format("%04d-%02d-%02dT%02d:%02d:%02dZ", z.getYear(), z.getMonthValue(), z.getDayOfMonth(),
        z.getHour(), z.getMinute(), z.getSecond());
  }
}
