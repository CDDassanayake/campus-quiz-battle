package com.quizbattle.client;

import com.quizbattle.client.service.AuthenticationFailure;
import com.quizbattle.client.service.GameClientService;
import com.quizbattle.common.*;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in actual M5 TLS verification. No M5 compile/runtime dependency in the M2 POM. */
public final class RealSecurityProbe {
    private static int checks;
    private RealSecurityProbe() { }
    private static void check(boolean valid, String description) {
        if (!valid) { throw new AssertionError(description); }
        checks++; System.out.println("PASS: " + description);
    }
    private static <T> T done(CompletableFuture<T> result) throws Exception { return result.get(12, TimeUnit.SECONDS); }
    private static String secret() { return UUID.randomUUID().toString() + UUID.randomUUID(); }
    private static String b64(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static void rejected(GameClientService service, ServerInfo info, String user, String password, boolean registration, String code) throws Exception {
        char[] owned = password.toCharArray();
        try { done(registration ? service.register(info, user, owned) : service.login(info, user, owned)); throw new AssertionError("Expected authentication rejection"); }
        catch (ExecutionException e) {
            if (!(e.getCause() instanceof AuthenticationFailure failure) || !failure.code().equals(code)
                    || failure.getCause() != null || failure.getMessage().contains(password)) {
                throw new AssertionError("Expected sanitized " + code + " without upstream data");
            }
        }
        check(new String(owned).chars().allMatch(c -> c == 0), "Rejected request clears caller password: " + code);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 4) { throw new IllegalArgumentException("Expected isolated M5 classes, server properties, trusted certificate and unrelated certificate paths."); }
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{Path.of(args[0]).toUri().toURL()}, RealSecurityProbe.class.getClassLoader())) {
            Class<?> usersType = loader.loadClass("com.quizbattle.security.UserStore");
            Class<?> tokensType = loader.loadClass("com.quizbattle.security.HmacTokenService");
            Class<?> serverType = loader.loadClass("com.quizbattle.security.TlsAuthServer");
            Class<?> clientType = loader.loadClass("com.quizbattle.security.TlsSecureLoginClient");
            Object users = usersType.getConstructor().newInstance();
            Object tokens = tokensType.getConstructor(String.class).newInstance(System.getenv("QB_TOKEN_SECRET"));
            TokenVerifier verifier = (TokenVerifier) tokens;
            Object server = serverType.getConstructor(AppConfig.class, usersType, tokensType).newInstance(AppConfig.load(args[1]), users, tokens);
            try (AutoCloseable actualServer = (AutoCloseable) server) {
                // Test-only reflection reads the OS-assigned port; M5 currently exposes no bound-port getter.
                var bound = serverType.getDeclaredField("serverSocket"); bound.setAccessible(true);
                int tlsPort = ((java.net.ServerSocket) bound.get(server)).getLocalPort();
                serverType.getMethod("start").invoke(server);
                SecureLoginClient client = (SecureLoginClient) clientType.getConstructor(String.class).newInstance(args[2]);
                String user = "tls_" + UUID.randomUUID().toString().substring(0, 8); String password = secret();
                try (FakeGameServer gateway = new FakeGameServer((socket, input) -> {
                    Message hello = MessageCodec.decode(input.readLine());
                    if (hello.type() != MessageType.HELLO || hello.fields().size() != 1 || !verifier.verify(hello.getField(0)).equals(Optional.of(user))) {
                        throw new AssertionError("Real M5 token not accepted by its verifier");
                    }
                    FakeGameServer.write(socket, "WELCOME|" + user + "\n");
                    String line; while ((line = input.readLine()) != null) {
                        if (line.equals("PING")) { FakeGameServer.write(socket, "PONG\n"); }
                        else { throw new AssertionError("Unexpected test gateway command"); }
                    }
                }); GameClientService service = new GameClientService(new FakeServerFinder(List.of()), client, null)) {
                    ServerInfo info = new ServerInfo("REAL TLS / TEST TCP GATEWAY", "localhost", gateway.port(), tlsPort);
                    char[] owned = password.toCharArray(); done(service.register(info, user, owned));
                    check(new String(owned).chars().allMatch(c -> c == 0), "M2 registration succeeds over actual M5 TLS and clears password");
                    rejected(service, info, user, password, true, "USER_EXISTS");
                    for (int i = 0; i < 6; i++) { rejected(service, info, user, secret(), false, "BAD_CREDENTIALS"); }
                    System.out.println("LIMIT: actual M5 returns BAD_CREDENTIALS after repeated failures; LOCKED is not implemented.");
                    String token = client.login("localhost", tlsPort, user, password.toCharArray());
                    check(verifier.verify(token).equals(Optional.of(user)), "Actual TLS login returns a token accepted by actual HmacTokenService");
                    check(verifier.verify(token + "tampered").isEmpty() && verifier.verify("invalid").isEmpty(), "Actual token verifier rejects malformed and tampered tokens");
                    String expired = Long.toString(Instant.now().minusSeconds(2).getEpochSecond());
                    var signing = tokensType.getDeclaredMethod("hmacSha256", String.class); signing.setAccessible(true);
                    String signature = (String) signing.invoke(tokens, user + "." + expired);
                    check(verifier.verify(b64(user) + "." + b64(expired) + "." + b64(signature)).isEmpty(), "Actual token verifier rejects a correctly signed expired M5-format token");
                    done(service.login(info, user, password.toCharArray()));
                    check(service.state().snapshot().authenticated() && user.equals(service.state().snapshot().username()), "Actual TLS token passes through M2 HELLO/WELCOME against an explicitly TEST TCP gateway");
                    done(service.ping()); ClientTestSupport.await(service.state(), s -> s.lastPong() != null);
                    done(service.disconnect()); gateway.awaitScript();
                    done(service.register(info, "new_" + user.substring(4), password.toCharArray()));
                    check(!service.state().snapshot().authenticated(), "Service can register again after disconnect without assuming sign-in");
                    // M5 currently emits raw delimiter-separated credentials rather than using MessageCodec.
                    rejected(service, info, "pipe_" + user.substring(4), password + "|extra", true, "BAD_MESSAGE");
                    System.out.println("LIMIT: M5 credential delimiter escaping is incompatible with frozen MessageCodec; owner fix pending.");
                }
                ServerInfo tlsOnly = new ServerInfo("REAL TLS", "localhost", 1, tlsPort);
                for (String cert : List.of(args[2] + ".missing", args[3])) {
                    SecureLoginClient bad = (SecureLoginClient) clientType.getConstructor(String.class).newInstance(cert);
                    try (GameClientService service = new GameClientService(new FakeServerFinder(List.of()), bad, null)) {
                        rejected(service, tlsOnly, "tls_probe", secret(), true, cert.endsWith(".missing") ? "CERTIFICATE_FILE" : "TLS_CERTIFICATE");
                    }
                }
                try (GameClientService service = new GameClientService(new FakeServerFinder(List.of()), client, null)) {
                    rejected(service, new ServerInfo("Hostname mismatch", "127.0.0.1", 1, tlsPort), "tls_probe", secret(), true, "TLS_CERTIFICATE");
                }
                int refused;
                try (java.net.ServerSocket reservation = new java.net.ServerSocket(0)) { refused = reservation.getLocalPort(); }
                try (GameClientService service = new GameClientService(new FakeServerFinder(List.of()), client, null)) {
                    rejected(service, new ServerInfo("Closed TLS port", "localhost", 1, refused), "tls_probe", secret(), true, "AUTH_CONNECTION");
                }
            }
        }
        System.out.println("REAL M5 TLS PROBE PASS: " + checks + " checks. No real NIO, UDP or LAN gameplay was exercised.");
    }
}
