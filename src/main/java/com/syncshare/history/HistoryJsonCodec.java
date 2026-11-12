package com.syncshare.history;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lightweight, zero-dependency RFC-8259 JSON encoder and strict parser for
 * {@link TransferRecord} persistence.
 */
public final class HistoryJsonCodec {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private HistoryJsonCodec() {
    }

    public static String encode(List<TransferRecord> records, Instant updatedAt) {
        Objects.requireNonNull(records, "records");
        Instant timestamp = updatedAt != null ? updatedAt : Instant.now();

        StringBuilder sb = new StringBuilder(256 + records.size() * 192);
        sb.append("{\n");
        sb.append("  \"schemaVersion\": ").append(CURRENT_SCHEMA_VERSION).append(",\n");
        sb.append("  \"updatedAt\": ");
        appendJsonString(sb, timestamp.toString());
        sb.append(",\n");
        sb.append("  \"records\": [");

        if (!records.isEmpty()) {
            sb.append('\n');
            for (int i = 0; i < records.size(); i++) {
                TransferRecord record = Objects.requireNonNull(records.get(i), "record");
                appendRecordObject(sb, record);
                if (i + 1 < records.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            sb.append("  ");
        }
        sb.append("]\n");
        sb.append("}\n");
        return sb.toString();
    }

    public static TransferHistoryRepository.LoadResult decode(String json) {
        if (json == null || json.isBlank()) {
            return TransferHistoryRepository.LoadResult.corrupted("History file is empty or unreadable JSON.");
        }
        Object parsed;
        try {
            parsed = new JsonParser(json).parseRoot();
        } catch (IllegalArgumentException ex) {
            return TransferHistoryRepository.LoadResult.corrupted("Malformed JSON syntax: " + ex.getMessage());
        }

        if (!(parsed instanceof Map<?, ?> root)) {
            return TransferHistoryRepository.LoadResult.corrupted("History root must be a JSON object.");
        }

        Object versionRaw = root.get("schemaVersion");
        if (versionRaw != null) {
            if (!(versionRaw instanceof Number versionNum) || versionNum.longValue() <= 0L) {
                return TransferHistoryRepository.LoadResult.corrupted("Invalid history schemaVersion.");
            }
        }

        Object recordsRaw = root.get("records");
        if (!(recordsRaw instanceof List<?> rawList)) {
            return TransferHistoryRepository.LoadResult.corrupted("Missing or invalid 'records' array in history JSON.");
        }

        List<TransferRecord> valid = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        int skipped = 0;

        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                skipped++;
                warnings.add("Entry #" + (i + 1) + " is not a JSON object and was skipped.");
                continue;
            }
            try {
                TransferRecord record = parseRecord(map);
                if (!seenIds.add(record.transferId())) {
                    skipped++;
                    warnings.add("Duplicate transferId '" + record.transferId() + "' at entry #" + (i + 1) + " was skipped.");
                    continue;
                }
                valid.add(record);
            } catch (RuntimeException ex) {
                skipped++;
                warnings.add("Entry #" + (i + 1) + " is invalid (" + ex.getMessage() + ") and was skipped.");
            }
        }

        return new TransferHistoryRepository.LoadResult(
            List.copyOf(valid),
            false,
            skipped,
            List.copyOf(warnings)
        );
    }

    private static TransferRecord parseRecord(Map<?, ?> map) {
        String transferId = requireString(map, "transferId");
        String fileName = requireString(map, "fileName");
        long fileSize = requireLong(map, "fileSize");
        TransferHistoryDirection direction = TransferHistoryDirection.valueOf(
            requireString(map, "direction").trim().toUpperCase(java.util.Locale.ROOT)
        );
        String peerInfo = requireString(map, "peerInfo");
        Instant transferredAt = Instant.parse(requireString(map, "transferredAt"));
        long durationMillis = requireLong(map, "durationMillis");
        if (durationMillis < 0L) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
        TransferHistoryStatus status = TransferHistoryStatus.valueOf(
            requireString(map, "status").trim().toUpperCase(java.util.Locale.ROOT)
        );
        String destination = optionalString(map, "destination");
        String summaryNote = optionalString(map, "summaryNote");

        return new TransferRecord(
            transferId,
            fileName,
            fileSize,
            direction,
            peerInfo,
            transferredAt,
            Duration.ofMillis(durationMillis),
            status,
            destination,
            summaryNote
        );
    }

    private static String requireString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String str) || str.isBlank()) {
            throw new IllegalArgumentException("missing or invalid string '" + key + "'");
        }
        return str;
    }

    private static String optionalString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return "";
        }
        if (!(value instanceof String str)) {
            throw new IllegalArgumentException("invalid string '" + key + "'");
        }
        return str;
    }

    private static long requireLong(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("missing or invalid number '" + key + "'");
        }
        if (value instanceof Double d && (d.isNaN() || d.isInfinite() || d % 1.0 != 0.0)) {
            throw new IllegalArgumentException("non-integer number '" + key + "'");
        }
        return number.longValue();
    }

    private static void appendRecordObject(StringBuilder sb, TransferRecord record) {
        sb.append("    {\n");
        appendStringField(sb, "transferId", record.transferId(), true);
        appendStringField(sb, "fileName", record.fileName(), true);
        appendNumberField(sb, "fileSize", record.fileSize(), true);
        appendStringField(sb, "direction", record.direction().name(), true);
        appendStringField(sb, "peerInfo", record.peerInfo(), true);
        appendStringField(sb, "transferredAt", record.transferredAt().toString(), true);
        appendNumberField(sb, "durationMillis", record.duration().toMillis(), true);
        appendStringField(sb, "status", record.status().name(), true);
        appendStringField(sb, "destination", record.destination(), true);
        appendStringField(sb, "summaryNote", record.summaryNote(), false);
        sb.append("    }");
    }

    private static void appendStringField(StringBuilder sb, String key, String value, boolean comma) {
        sb.append("      ");
        appendJsonString(sb, key);
        sb.append(": ");
        appendJsonString(sb, value != null ? value : "");
        if (comma) {
            sb.append(',');
        }
        sb.append('\n');
    }

    private static void appendNumberField(StringBuilder sb, String key, long value, boolean comma) {
        sb.append("      ");
        appendJsonString(sb, key);
        sb.append(": ").append(value);
        if (comma) {
            sb.append(',');
        }
        sb.append('\n');
    }

    private static void appendJsonString(StringBuilder sb, String raw) {
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class JsonParser {
        private final String input;
        private int pos;

        JsonParser(String input) {
            this.input = input;
        }

        Object parseRoot() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (pos < input.length()) {
                throw new IllegalArgumentException("Unexpected trailing content at position " + pos);
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= input.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char c = input.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + pos);
                }
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw new IllegalArgumentException("Expected string key at position " + pos);
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek('}')) {
                    pos++;
                    return map;
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' at position " + pos);
                }
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek(']')) {
                    pos++;
                    return list;
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']' at position " + pos);
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw new IllegalArgumentException("Unescaped control character in string at position " + (pos - 1));
                }
                if (c == '\\') {
                    if (pos >= input.length()) {
                        throw new IllegalArgumentException("Unterminated escape sequence");
                    }
                    char esc = input.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > input.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape at position " + pos);
                            }
                            String hex = input.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ex) {
                                throw new IllegalArgumentException("Invalid hex unicode escape '\\u" + hex + "'");
                            }
                        }
                        default -> throw new IllegalArgumentException("Invalid escape '\\" + esc + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string literal");
        }

        private Boolean parseBoolean() {
            if (input.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (input.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid boolean literal at position " + pos);
        }

        private Object parseNull() {
            if (input.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid null literal at position " + pos);
        }

        private Number parseNumber() {
            int start = pos;
            if (input.charAt(pos) == '-') {
                pos++;
            }
            if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                throw new IllegalArgumentException("Invalid number at position " + start);
            }
            if (input.charAt(pos) == '0') {
                pos++;
                if (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    throw new IllegalArgumentException("Leading zero in JSON number at position " + start);
                }
            } else {
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }

            boolean isFractionOrExp = false;
            if (pos < input.length() && input.charAt(pos) == '.') {
                isFractionOrExp = true;
                pos++;
                if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                    throw new IllegalArgumentException("Invalid decimal fraction at position " + start);
                }
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                isFractionOrExp = true;
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                    pos++;
                }
                if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                    throw new IllegalArgumentException("Invalid exponent in JSON number at position " + start);
                }
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }

            String token = input.substring(start, pos);
            try {
                if (isFractionOrExp) {
                    return Double.parseDouble(token);
                }
                return Long.parseLong(token);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Number out of range: " + token);
            }
        }

        private void skipWhitespace() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private boolean peek(char expected) {
            return pos < input.length() && input.charAt(pos) == expected;
        }

        private void expect(char expected) {
            if (pos >= input.length() || input.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at position " + pos);
            }
            pos++;
        }
    }
}
