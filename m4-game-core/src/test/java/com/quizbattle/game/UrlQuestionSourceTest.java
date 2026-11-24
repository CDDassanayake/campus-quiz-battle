package com.quizbattle.game;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlQuestionSourceTest {

    private static final String HEADER =
            "id,category,text,optionA,optionB,optionC,optionD,correctIndex";

    private static final String VALID_CSV = HEADER + "\n"
            + "1,Remote,\"What is 1, 2 or 3?\",A,B,C,D,2\n"
            + "2,Remote,Second question,A,B,C,D,0\n";

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/questions", exchange -> respond(exchange, 200, VALID_CSV));
        server.createContext("/error", exchange -> respond(exchange, 500, "server error"));
        server.createContext("/malformed", exchange -> respond(exchange, 200, "this is not a question CSV"));
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, VALID_CSV);
        });
        server.createContext("/huge", exchange -> respond(exchange, 200, VALID_CSV.repeat(200)));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void loadsQuestionsFromTheUrl() throws IOException {
        CountingSource fallback = new CountingSource();

        List<Question> questions = new UrlQuestionSource(baseUrl + "/questions", fallback).load();

        assertEquals(2, questions.size());
        assertEquals("What is 1, 2 or 3?", questions.get(0).text());
        assertEquals(0, fallback.calls.get(), "remote success must not touch the fallback");
    }

    @Test
    void fallsBackOnHttp500() throws IOException {
        CountingSource fallback = new CountingSource();

        List<Question> questions = new UrlQuestionSource(baseUrl + "/error", fallback).load();

        assertEquals(CountingSource.QUESTION.id(), questions.get(0).id());
        assertEquals(1, fallback.calls.get());
    }

    @Test
    void fallsBackOnMalformedCsv() throws IOException {
        CountingSource fallback = new CountingSource();

        List<Question> questions = new UrlQuestionSource(baseUrl + "/malformed", fallback).load();

        assertEquals(CountingSource.QUESTION.id(), questions.get(0).id());
        assertEquals(1, fallback.calls.get());
    }

    @Test
    void fallsBackOnReadTimeout() throws IOException {
        CountingSource fallback = new CountingSource();
        UrlQuestionSource source = new UrlQuestionSource(
                baseUrl + "/slow", fallback, 200, 150, UrlQuestionSource.DEFAULT_MAX_BYTES);

        List<Question> questions = source.load();

        assertEquals(CountingSource.QUESTION.id(), questions.get(0).id());
        assertEquals(1, fallback.calls.get());
    }

    @Test
    void fallsBackWhenTheBodyIsTooLarge() throws IOException {
        CountingSource fallback = new CountingSource();
        UrlQuestionSource source = new UrlQuestionSource(
                baseUrl + "/huge", fallback, 1_000, 1_000, 512);

        List<Question> questions = source.load();

        assertEquals(CountingSource.QUESTION.id(), questions.get(0).id());
        assertEquals(1, fallback.calls.get());
    }

    @Test
    void fallsBackWhenTheConnectionIsRefused() throws IOException {
        CountingSource fallback = new CountingSource();
        String deadUrl = baseUrl + "/questions";
        server.stop(0);
        server = null;

        List<Question> questions = new UrlQuestionSource(deadUrl, fallback, 200, 200, 4096).load();

        assertEquals(CountingSource.QUESTION.id(), questions.get(0).id());
        assertEquals(1, fallback.calls.get());
    }

    @Test
    void blankUrlUsesTheLocalSourceDirectly() throws IOException {
        CountingSource fallback = new CountingSource();

        List<Question> questions = new UrlQuestionSource("   ", fallback, 200, 200, 4096).load();

        assertEquals(1, questions.size());
        assertEquals(1, fallback.calls.get());
    }

    @Test
    void unsupportedSchemeFallsBack() throws IOException {
        CountingSource fallback = new CountingSource();

        List<Question> questions = new UrlQuestionSource("ftp://example.com/questions.csv", fallback).load();

        assertEquals(1, questions.size());
        assertEquals(1, fallback.calls.get());
    }

    @Test
    void productionTimeoutsAndLimitMatchTheAssignment() {
        assertTrue(UrlQuestionSource.DEFAULT_CONNECT_TIMEOUT_MILLIS == 3_000);
        assertTrue(UrlQuestionSource.DEFAULT_READ_TIMEOUT_MILLIS == 5_000);
        assertTrue(UrlQuestionSource.DEFAULT_MAX_BYTES == 1_048_576L);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class CountingSource implements QuestionSource {

        static final Question QUESTION = new Question(
                99, "Local", "Local fallback question?",
                List.of("A", "B", "C", "D"), 1);

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<Question> load() {
            calls.incrementAndGet();
            return List.of(QUESTION);
        }
    }
}
