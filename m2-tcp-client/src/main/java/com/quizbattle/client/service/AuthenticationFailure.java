package com.quizbattle.client.service;

import java.io.IOException;
import java.util.Set;

/** M2-local, sanitized failure; common's IOException contract remains unchanged. */
public final class AuthenticationFailure extends IOException {
    private final String code;
    private AuthenticationFailure(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
    public static AuthenticationFailure from(Throwable error, boolean registration) {
        String raw = error.getMessage() == null ? "" : error.getMessage();
        // Actual M5 TlsSecureLoginClient wraps ERROR as "Server error: CODE - text".
        // Recognize only its anchored envelope; never search arbitrary upstream text.
        var wrapped = java.util.regex.Pattern.compile("^Server error: ([A-Z_]+)(?= - |$)").matcher(raw);
        if (wrapped.find()) { raw = wrapped.group(1); }
        if (raw.startsWith("ERROR|")) { raw = raw.substring(6); }
        String candidate = raw.split("[|:\\s]", 2)[0];
        String code = Set.of("USER_EXISTS", "BAD_MESSAGE", "LOCKED", "BAD_CREDENTIALS").contains(candidate) ? candidate : "AUTH_UNAVAILABLE";
        if (code.equals("AUTH_UNAVAILABLE")) {
            Throwable cause = error;
            for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
                if (cause instanceof javax.net.ssl.SSLException || cause instanceof java.security.cert.CertificateException) { code = "TLS_CERTIFICATE"; break; }
                if (cause instanceof java.nio.file.NoSuchFileException || cause instanceof java.io.FileNotFoundException) { code = "CERTIFICATE_FILE"; break; }
                if (cause instanceof java.net.SocketTimeoutException) { code = "AUTH_TIMEOUT"; break; }
                if (cause instanceof java.net.ConnectException) { code = "AUTH_CONNECTION"; break; }
                if (cause instanceof java.net.UnknownHostException) { code = "AUTH_HOST"; break; }
            }
        }
        String message = switch (code) {
            case "USER_EXISTS" -> "That username already exists. Choose another username or sign in.";
            case "BAD_MESSAGE" -> "The authentication service rejected the request. Check the fields and retry.";
            case "LOCKED" -> "This account is temporarily locked. Try again later.";
            case "BAD_CREDENTIALS" -> "Username or password was not accepted.";
            case "TLS_CERTIFICATE" -> "TLS verification failed. Check the trusted server certificate, hostname and TLS settings.";
            case "CERTIFICATE_FILE" -> "The server certificate could not be read. Ask the server owner for its public certificate and check the configured path.";
            case "AUTH_TIMEOUT" -> "The TLS server did not respond in time. Check its address and port, then retry.";
            case "AUTH_CONNECTION" -> "The TLS server is unavailable. Check its address, port and whether it is running.";
            case "AUTH_HOST" -> "The authentication hostname could not be resolved. Check the server address.";
            default -> registration ? "Registration failed. Check account details and TLS server settings."
                    : "Login failed. Check credentials, certificate and server settings.";
        };
        return new AuthenticationFailure(code, message); // Never attach the upstream cause or text.
    }
}
