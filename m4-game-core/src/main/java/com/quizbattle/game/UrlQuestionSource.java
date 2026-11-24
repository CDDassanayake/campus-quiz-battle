package com.quizbattle.game;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Downloads a question CSV from an HTTP(S) URL and falls back to a local source on any
 * failure.
 *
 * <p>This is M4's direct {@code java.net.URI}/{@code java.net.URL} contribution. Only the
 * JDK HTTP stack is used: no external libraries. Connect and read timeouts are bounded,
 * and the response body is capped so a malicious or broken server cannot exhaust the
 * server's memory.</p>
 *
 * <p>Failures never propagate to the game: after logging the failure type (never the
 * full URL, which may contain a token) the fallback source is used.</p>
 */
public final class UrlQuestionSource implements QuestionSource {

    private static final Logger LOG = Logger.getLogger(UrlQuestionSource.class.getName());

    /** Connect timeout required by the assignment. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 3_000;
    /** Read timeout required by the assignment. */
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 5_000;
    /** Maximum number of response bytes accepted. */
    public static final long DEFAULT_MAX_BYTES = 1_048_576L;

    private final String url;
    private final QuestionSource fallback;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final long maxBytes;
    private final CsvQuestionParser parser = new CsvQuestionParser();

    /**
     * Creates a URL source with the production timeouts and 1 MB body limit.
     *
     * @param url      the configured URL; may be blank, in which case the fallback is used directly
     * @param fallback the local source used when the URL is absent or fails
     */
    public UrlQuestionSource(String url, QuestionSource fallback) {
        this(url, fallback, DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_READ_TIMEOUT_MILLIS, DEFAULT_MAX_BYTES);
    }

    UrlQuestionSource(String url, QuestionSource fallback, int connectTimeoutMillis, int readTimeoutMillis, long maxBytes) {
        this.url = url == null ? "" : url.trim();
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        if (connectTimeoutMillis < 1 || readTimeoutMillis < 1) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxBytes = maxBytes;
    }

    @Override
    public List<Question> load() throws IOException {
        if (url.isBlank()) {
            return fallback.load();
        }
        try {
            List<Question> questions = download();
            if (questions.isEmpty()) {
                throw new IOException("Remote question file contained no valid questions");
            }
            return questions;
        } catch (IOException | RuntimeException e) {
            // Do not log the URL itself: it may contain credentials or tokens.
            LOG.log(Level.WARNING,
                    "Remote question import failed ({0}); using local questions",
                    e.getClass().getSimpleName());
            return fallback.load();
        }
    }

    private List<Question> download() throws IOException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IOException("Invalid question URL", e);
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IOException("Unsupported question URL scheme");
        }

        URLConnection connection = uri.toURL().openConnection();
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeoutMillis);
        connection.setRequestProperty("User-Agent", "CampusQuizBattle/1.0 (M4)");
        connection.setRequestProperty("Accept", "text/csv,text/plain,*/*");

        HttpURLConnection http = connection instanceof HttpURLConnection h ? h : null;
        try {
            if (http != null) {
                http.setInstanceFollowRedirects(true);
                int status = http.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new IOException("HTTP status " + status);
                }
            }
            try (InputStream in = connection.getInputStream()) {
                byte[] body = readBounded(in);
                return parser.parse(new String(body, StandardCharsets.UTF_8));
            }
        } finally {
            if (http != null) {
                http.disconnect();
            }
        }
    }

    private byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Question file exceeds " + maxBytes + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
