package com.syncshare.service;

import com.syncshare.model.AppSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight, zero-dependency RFC-8259 JSON encoder and strict parser for
 * {@link AppSettings} persistence.
 */
public final class SettingsJsonCodec {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private SettingsJsonCodec() {
    }

    public static String encode(AppSettings settings) {
        Objects.requireNonNull(settings, "settings");
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        sb.append("  \"schemaVersion\": ").append(CURRENT_SCHEMA_VERSION).append(",\n");
        appendStringField(sb, "deviceAlias", settings.deviceAlias(), true);
        appendNumberField(sb, "listeningPort", settings.listeningPort(), true);
        appendStringField(sb, "receiveDirectory", settings.receiveDirectory(), true);
        appendStringField(sb, "downloadDirectory", settings.downloadDirectory(), true);
        appendBooleanField(sb, "requireApproval", settings.requireApproval(), true);
        appendBooleanField(sb, "verifySha256", settings.verifySha256(), true);
        appendBooleanField(sb, "autoStartReceiver", settings.autoStartReceiver(), true);
        appendBooleanField(sb, "startMinimized", settings.startMinimized(), true);
        appendBooleanField(sb, "notificationsEnabled", settings.notificationsEnabled(), true);
        appendBooleanField(sb, "notifyOnTransferComplete", settings.notifyOnTransferComplete(), true);
        appendBooleanField(sb, "notifyOnIncomingRequest", settings.notifyOnIncomingRequest(), true);
        appendBooleanField(sb, "soundEnabled", settings.soundEnabled(), true);
        appendStringField(sb, "theme", settings.theme(), false);
        sb.append("}\n");
        return sb.toString();
    }

    public static SettingsRepository.LoadResult decode(String json, AppSettings defaults) {
        AppSettings fallbackDefaults = defaults != null ? defaults : AppSettings.defaults();
        if (json == null || json.isBlank()) {
            return SettingsRepository.LoadResult.corrupted(
                fallbackDefaults,
                "Settings file is empty or unreadable JSON."
            );
        }

        Object parsed;
        try {
            parsed = new JsonParser(json).parseRoot();
        } catch (IllegalArgumentException ex) {
            return SettingsRepository.LoadResult.corrupted(
                fallbackDefaults,
                "Malformed JSON syntax in settings: " + ex.getMessage()
            );
        }

        if (!(parsed instanceof Map<?, ?> root)) {
            return SettingsRepository.LoadResult.corrupted(
                fallbackDefaults,
                "Settings root must be a JSON object."
            );
        }

        List<String> warnings = new ArrayList<>();
        AppSettings.Builder builder = fallbackDefaults.toBuilder();

        // schemaVersion
        Object versionRaw = root.get("schemaVersion");
        if (versionRaw instanceof Number num) {
            long version = num.longValue();
            if (version > CURRENT_SCHEMA_VERSION) {
                warnings.add("Settings schema version " + version + " is newer than supported " + CURRENT_SCHEMA_VERSION + ".");
            }
        }

        // deviceAlias
        Object aliasRaw = root.get("deviceAlias");
        if (aliasRaw instanceof String str && !str.isBlank()) {
            builder.deviceAlias(str.trim());
        } else if (aliasRaw != null) {
            warnings.add("Invalid 'deviceAlias' value; using default.");
        }

        // listeningPort
        Object portRaw = root.get("listeningPort");
        if (portRaw instanceof Number num) {
            int port = num.intValue();
            if (port >= AppSettings.MIN_PORT && port <= AppSettings.MAX_PORT) {
                builder.listeningPort(port);
            } else {
                warnings.add("Port " + port + " is out of valid range (1024-65535); using default.");
            }
        } else if (portRaw != null) {
            warnings.add("Invalid 'listeningPort' value; using default.");
        }

        // receiveDirectory
        Object recvDirRaw = root.get("receiveDirectory");
        if (recvDirRaw instanceof String str && !str.isBlank()) {
            builder.receiveDirectory(str.trim());
        } else if (recvDirRaw != null) {
            warnings.add("Invalid 'receiveDirectory' value; using default.");
        }

        // downloadDirectory
        Object dlDirRaw = root.get("downloadDirectory");
        if (dlDirRaw instanceof String str && !str.isBlank()) {
            builder.downloadDirectory(str.trim());
        } else if (dlDirRaw != null) {
            warnings.add("Invalid 'downloadDirectory' value; using default.");
        }

        // Boolean flags
        if (root.get("requireApproval") instanceof Boolean b) {
            builder.requireApproval(b);
        }
        if (root.get("verifySha256") instanceof Boolean b) {
            builder.verifySha256(b);
        }
        if (root.get("autoStartReceiver") instanceof Boolean b) {
            builder.autoStartReceiver(b);
        }
        if (root.get("startMinimized") instanceof Boolean b) {
            builder.startMinimized(b);
        }
        if (root.get("notificationsEnabled") instanceof Boolean b) {
            builder.notificationsEnabled(b);
        }
        if (root.get("notifyOnTransferComplete") instanceof Boolean b) {
            builder.notifyOnTransferComplete(b);
        }
        if (root.get("notifyOnIncomingRequest") instanceof Boolean b) {
            builder.notifyOnIncomingRequest(b);
        }
        if (root.get("soundEnabled") instanceof Boolean b) {
            builder.soundEnabled(b);
        }

        // theme
        Object themeRaw = root.get("theme");
        if (themeRaw instanceof String str && !str.isBlank()) {
            builder.theme(str.trim());
        }

        AppSettings loaded = builder.build();
        return new SettingsRepository.LoadResult(
            loaded,
            !warnings.isEmpty(),
            false,
            List.copyOf(warnings)
        );
    }

    private static void appendStringField(StringBuilder sb, String key, String value, boolean comma) {
        sb.append("  ");
        appendJsonString(sb, key);
        sb.append(": ");
        appendJsonString(sb, value != null ? value : "");
        if (comma) {
            sb.append(',');
        }
        sb.append('\n');
    }

    private static void appendNumberField(StringBuilder sb, String key, long value, boolean comma) {
        sb.append("  ");
        appendJsonString(sb, key);
        sb.append(": ").append(value);
        if (comma) {
            sb.append(',');
        }
        sb.append('\n');
    }

    private static void appendBooleanField(StringBuilder sb, String key, boolean value, boolean comma) {
        sb.append("  ");
        appendJsonString(sb, key);
        sb.append(": ").append(value ? "true" : "false");
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
