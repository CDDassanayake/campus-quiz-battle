package com.quizbattle.client.ui;

import com.quizbattle.common.ServerInfo;
import java.util.Arrays;

/** Syntactic validation only: no DNS, sockets, password policy or server rules. */
public final class FormValidation {
    private FormValidation() { }
    public static String host(String value) {
        String host = value == null ? "" : value.trim();
        if (host.isEmpty() || host.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                || host.matches(".*[/\\\\|?#@].*") || host.chars().filter(c -> c == ':').count() == 1
                || (host.startsWith("[") && !host.endsWith("]"))) {
            throw new IllegalArgumentException("Enter a hostname or IP address, without a URL or spaces.");
        }
        return host;
    }
    public static int port(String value) {
        int port = positive(value, "Enter a port from 1 to 65535.");
        if (port > 65535) { throw new IllegalArgumentException("Enter a port from 1 to 65535."); }
        return port;
    }
    public static ServerInfo server(String host, String gateway, String tls) {
        String name = host(host); return new ServerInfo("Manual server", name, port(gateway), port(tls));
    }
    public static String username(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("Username must be 3–16 letters, digits or underscores.");
        }
        return value;
    }
    public static void password(char[] value) {
        if (value == null || value.length == 0) { throw new IllegalArgumentException("Enter a password."); }
    }
    public static void registration(String name, char[] password, char[] confirmation) {
        username(name); password(password);
        if (!Arrays.equals(password, confirmation)) { throw new IllegalArgumentException("Passwords do not match."); }
    }
    public static String roomName(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_ ]{1,24}")) {
            throw new IllegalArgumentException("Room name must use 1–24 letters, digits, spaces or underscores.");
        }
        return value;
    }
    public static int questionCount(String value) { return positive(value, "Enter a positive whole number of questions."); }
    private static int positive(String value, String error) {
        try {
            if (value == null || !value.matches("[0-9]+")) { throw new NumberFormatException(); }
            int number = Integer.parseInt(value); if (number < 1) { throw new NumberFormatException(); } return number;
        } catch (NumberFormatException e) { throw new IllegalArgumentException(error); }
    }
}
