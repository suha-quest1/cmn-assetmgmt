package com.vishworks.assetmgmt.json;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * The single {@link ObjectMapper} for every JSON document this backend reads or writes
 * (HTTP responses, process variables, report files). Jackson's defaults are overridden so
 * the wire format stays stable and human-readable:
 *
 * <ul>
 *   <li>doubles are written in plain notation ('5000000', '0.82', never '5.0E7') — these are
 *       monetary amounts in documents people read, so exponent notation is not acceptable;
 *   <li>'&lt;', '&gt;', '&amp;', U+2028 and U+2029 are escaped, so a payload can be embedded
 *       in an HTML page or a JS string without further quoting;
 *   <li>Postgres timestamps are written as RFC 3339 with nanoseconds, in UTC ('Z');
 *   <li>decoding is lenient in the ways the dashboard and webhooks rely on — unknown fields are
 *       ignored and field names match case-insensitively — but rejects type mismatches (e.g. a
 *       number where a string is expected), and a null field leaves the field's default value.
 * </ul>
 *
 * <p>The exact bytes are pinned by the fixtures in {@code src/test/resources/golden}; changing
 * anything here will change those documents.
 */
public final class JsonCodec {
  private JsonCodec() {}

  public static final ObjectMapper MAPPER = build();

  private static ObjectMapper build() {
    SimpleModule m = new SimpleModule("json-codec");
    JsonSerializer<Double> dbl = new JsonSerializer<>() {
      @Override
      public void serialize(Double v, JsonGenerator g, SerializerProvider p) throws IOException {
        try {
          g.writeNumber(com.vishworks.assetmgmt.util.Format.jsonFloat(v));
        } catch (IllegalArgumentException e) {
          throw new com.fasterxml.jackson.core.JsonGenerationException(e.getMessage(), g);
        }
      }
    };
    m.addSerializer(Double.class, dbl);
    m.addSerializer(double.class, dbl);
    m.addSerializer(LocalDateTime.class, new JsonSerializer<>() {
      @Override
      public void serialize(LocalDateTime v, JsonGenerator g, SerializerProvider p) throws IOException {
        g.writeString(com.vishworks.assetmgmt.util.Format.rfc3339Nano(v));
      }
    });
    m.addSerializer(OffsetDateTime.class, new JsonSerializer<>() {
      @Override
      public void serialize(OffsetDateTime v, JsonGenerator g, SerializerProvider p) throws IOException {
        g.writeString(com.vishworks.assetmgmt.util.Format.rfc3339Nano(v.toZonedDateTime()));
      }
    });

    JsonMapper mapper = JsonMapper.builder()
        .addModule(m)
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .disable(MapperFeature.AUTO_DETECT_GETTERS)
        .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        .build();
    // An explicit null leaves the field at its default rather than overwriting it with null.
    mapper.setDefaultSetterInfo(JsonSetter.Value.forValueNulls(Nulls.SKIP));
    // Refuse silent coercion: a number for a string, a string for a number, etc. is an error.
    for (LogicalType t : new LogicalType[] {LogicalType.Textual, LogicalType.Integer, LogicalType.Float, LogicalType.Boolean}) {
      mapper.coercionConfigFor(t)
          .setCoercion(CoercionInputShape.Integer, t == LogicalType.Integer || t == LogicalType.Float ? CoercionAction.TryConvert : CoercionAction.Fail)
          .setCoercion(CoercionInputShape.Float, t == LogicalType.Float ? CoercionAction.TryConvert : CoercionAction.Fail)
          .setCoercion(CoercionInputShape.String, t == LogicalType.Textual ? CoercionAction.TryConvert : CoercionAction.Fail)
          .setCoercion(CoercionInputShape.Boolean, t == LogicalType.Boolean ? CoercionAction.TryConvert : CoercionAction.Fail)
          .setCoercion(CoercionInputShape.EmptyString, t == LogicalType.Textual ? CoercionAction.TryConvert : CoercionAction.Fail);
    }
    mapper.getFactory().setCharacterEscapes(new HtmlSafeEscapes());
    return mapper;
  }

  /** Compact JSON, no trailing newline. */
  public static String toJson(Object v) {
    try {
      return MAPPER.writeValueAsString(v);
    } catch (IOException e) {
      throw new IllegalStateException("json: " + e.getMessage(), e);
    }
  }

  /** Compact JSON followed by a newline, for writing straight to a response body. */
  public static byte[] encode(Object v) {
    return (toJson(v) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  /** Pretty-printed JSON, two-space indent. */
  public static String toJsonIndented(Object v) {
    try {
      return MAPPER.writer(new TwoSpaceIndentPrinter()).writeValueAsString(v);
    } catch (IOException e) {
      throw new IllegalStateException("json: " + e.getMessage(), e);
    }
  }

  /** Decodes into a new value; a bare JSON null yields a default-constructed instance. */
  public static <T> T fromJson(String json, Class<T> type) throws IOException {
    T v = MAPPER.readValue(json, type);
    if (v == null) {
      try {
        return type.getDeclaredConstructor().newInstance();
      } catch (ReflectiveOperationException e) {
        return null;
      }
    }
    return v;
  }

  /**
   * Decodes a request body. An empty body is reported as "EOF"; anything else carries
   * Jackson's parse message, trimmed to its first line. Both reach the client verbatim,
   * so {@code MockServicesTest} asserts on these strings.
   */
  public static <T> T decodeBody(byte[] body, Class<T> type) throws DecodeException {
    String s = new String(body, StandardCharsets.UTF_8);
    if (s.isBlank()) {
      throw new DecodeException("EOF");
    }
    try {
      return fromJson(s, type);
    } catch (IOException e) {
      String msg = e.getMessage();
      int nl = msg == null ? -1 : msg.indexOf('\n');
      throw new DecodeException(nl > 0 ? msg.substring(0, nl) : String.valueOf(msg));
    }
  }

  /** Error returned by {@link #decodeBody}. */
  public static final class DecodeException extends Exception {
    public DecodeException(String m) {
      super(m);
    }
  }

  /** HTML-safe string escaping: lower-case hex, with \b and \f written in short form. */
  static final class HtmlSafeEscapes extends CharacterEscapes {
    private final int[] codes;

    HtmlSafeEscapes() {
      codes = CharacterEscapes.standardAsciiEscapesForJSON();
      for (int c = 0; c < 0x20; c++) {
        codes[c] = CharacterEscapes.ESCAPE_CUSTOM;
      }
      codes['<'] = CharacterEscapes.ESCAPE_CUSTOM;
      codes['>'] = CharacterEscapes.ESCAPE_CUSTOM;
      codes['&'] = CharacterEscapes.ESCAPE_CUSTOM;
    }

    @Override
    public int[] getEscapeCodesForAscii() {
      return codes;
    }

    @Override
    public SerializableString getEscapeSequence(int ch) {
      return switch (ch) {
        case '\n' -> new SerializedString("\\n");
        case '\r' -> new SerializedString("\\r");
        case '\t' -> new SerializedString("\\t");
        case '\b' -> new SerializedString("\\b");
        case '\f' -> new SerializedString("\\f");
        case 0x2028, 0x2029 -> new SerializedString(String.format("\\u%04x", ch));
        default -> ch < 0x20 || ch == '<' || ch == '>' || ch == '&'
            ? new SerializedString(String.format("\\u%04x", ch))
            : null;
      };
    }
  }

  /** Two-space indent, no space before the ':' in a field, no root separator. */
  static final class TwoSpaceIndentPrinter extends DefaultPrettyPrinter {
    TwoSpaceIndentPrinter() {
      super(Separators.createDefaultInstance()
          .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
          .withRootSeparator(""));
      DefaultIndenter ind = new DefaultIndenter("  ", "\n");
      indentObjectsWith(ind);
      indentArraysWith(ind);
    }

    TwoSpaceIndentPrinter(TwoSpaceIndentPrinter base) {
      super(base);
    }

    @Override
    public DefaultPrettyPrinter createInstance() {
      return new TwoSpaceIndentPrinter(this);
    }

    @Override
    public void writeEndObject(JsonGenerator g, int nrOfEntries) throws IOException {
      if (!_objectIndenter.isInline()) {
        --_nesting;
      }
      if (nrOfEntries > 0) {
        _objectIndenter.writeIndentation(g, _nesting);
      }
      g.writeRaw('}');
    }

    @Override
    public void writeEndArray(JsonGenerator g, int nrOfValues) throws IOException {
      if (!_arrayIndenter.isInline()) {
        --_nesting;
      }
      if (nrOfValues > 0) {
        _arrayIndenter.writeIndentation(g, _nesting);
      }
      g.writeRaw(']');
    }
  }
}
