package com.shinro.migration.cmnassetmgmt.activities;

import static com.shinro.migration.cmnassetmgmt.activities.Fmt.sprintf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Tiny dependency-free PDF 1.4 generator (Helvetica / Courier text only), A4 pages. */
final class Pdf {
  private record Line(String text, String font, double size, double gap) {}

  private final List<Line> lines = new ArrayList<>();

  void title(String s) {
    lines.add(new Line(s, "F2", 16, 6));
  }

  void heading(String s) {
    lines.add(new Line(s, "F2", 11, 10));
  }

  void text(String s) {
    lines.add(new Line(s, "F1", 9.5, 0));
  }

  void mono(String s) {
    lines.add(new Line(s, "F3", 8.5, 0));
  }

  /** Keeps to WinAnsi-safe ASCII and escapes PDF string delimiters. */
  static String esc(String s) {
    StringBuilder b = new StringBuilder();
    s.codePoints().forEach(c -> {
      if (c > 126) {
        switch (c) {
          case '—', '–' -> c = '-';
          case '≥' -> c = '>';
          default -> c = '?';
        }
      }
      switch (c) {
        case '\\' -> b.append("\\\\");
        case '(' -> b.append("\\(");
        case ')' -> b.append("\\)");
        default -> b.appendCodePoint(c);
      }
    });
    return b.toString();
  }

  byte[] bytes() {
    final double pageW = 595.0, pageH = 842.0;
    final double marginL = 50.0, top = 790.0;
    final double bottom = 60.0;
    List<String> pages = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    double y = top;
    int pageNo = 1;
    for (Line l : lines) {
      double h = l.size * 1.35 + l.gap;
      if (y - h < bottom) {
        cur.append(footer(marginL, pageNo));
        pages.add(cur.toString());
        cur.setLength(0);
        y = top;
        pageNo++;
      }
      y -= h;
      cur.append(sprintf("BT /%s %.1f Tf %.1f %.1f Td (%s) Tj ET\n", l.font, l.size, marginL, y, esc(l.text)));
    }
    cur.append(footer(marginL, pageNo));
    pages.add(cur.toString());

    Out out = new Out();
    out.raw("%PDF-1.4\n");
    out.bytes(new byte[] {'%', (byte) 0xe2, (byte) 0xe3, (byte) 0xcf, (byte) 0xd3, '\n'});
    int n = pages.size();
    out.obj("<< /Type /Catalog /Pages 2 0 R >>");
    List<String> kids = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      kids.add(sprintf("%d 0 R", 6 + i * 2));
    }
    out.obj(sprintf("<< /Type /Pages /Kids [%s] /Count %d >>", String.join(" ", kids), n));
    out.obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
    out.obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
    out.obj("<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>");
    for (int i = 0; i < n; i++) {
      String content = pages.get(i);
      out.obj(sprintf("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.0f %.0f] /Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >> >> /Contents %d 0 R >>",
          pageW, pageH, 7 + i * 2));
      out.obj(sprintf("<< /Length %d >>\nstream\n%sendstream", content.getBytes(StandardCharsets.UTF_8).length, content));
    }
    int xref = out.size();
    out.raw(sprintf("xref\n0 %d\n0000000000 65535 f \n", out.offsets.size() + 1));
    for (int o : out.offsets) {
      out.raw(sprintf("%010d 00000 n \n", o));
    }
    out.raw(sprintf("trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%EOF\n", out.offsets.size() + 1, xref));
    return out.toByteArray();
  }

  private static String footer(double marginL, int pageNo) {
    return sprintf("BT /F1 8 Tf %.1f %.1f Td (Page %d - Intelligent Capital Call & Liquidity Orchestration - Temporal) Tj ET\n",
        marginL, 30.0, pageNo);
  }

  private static final class Out {
    final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    final List<Integer> offsets = new ArrayList<>();

    void raw(String s) {
      bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    void bytes(byte[] b) {
      buf.writeBytes(b);
    }

    int size() {
      return buf.size();
    }

    void obj(String body) {
      offsets.add(buf.size());
      raw(sprintf("%d 0 obj\n%s\nendobj\n", offsets.size(), body));
    }

    byte[] toByteArray() {
      return buf.toByteArray();
    }
  }
}
