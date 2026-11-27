package com.quizbattle.client;

import com.quizbattle.client.service.GameClientService;
import com.quizbattle.client.ui.ConsoleClient;
import com.quizbattle.common.AppConfig;
import com.quizbattle.common.ServerInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.List;

/** Independent console demo: REAL loopback TCP, FAKE login/discovery/live feed. */
public final class MockQuizDemo {
    private MockQuizDemo() { }

    public static void main(String[] args) throws Exception {
        System.out.println("TEST DEMO: actual TCP; authentication, discovery and live-feed services are MOCKS.");
        System.out.println("Choose discovery, select server 1, login as alice with any nonempty demo password.");
        System.out.println("Create a room or join r1, then start. Answer using: answer q1 2 / answer q2 1");
        try (FakeGameServer server = new FakeGameServer(MockQuizDemo::play);
             GameClientService service = new GameClientService(
                     new FakeServerFinder(List.of(new ServerInfo("MOCK Quiz server", "localhost", server.port(), server.port()))),
                     new FakeSecureLoginClient(), FakeLiveFeedReceiver::new)) {
            ConsoleClient.system(service, AppConfig.load("config/client.properties")).run();
            service.awaitTermination(Duration.ofSeconds(3));
        }
    }

    /** A deterministic two-question script, reused by the service/console tests. */
    static void play(Socket socket, BufferedReader input) throws Exception {
        socket.setSoTimeout(0); // Human-paced demo; close() still cancels all socket reads.
        expect(input, "HELLO|" + FakeSecureLoginClient.TOKEN);
        FakeGameServer.write(socket, "WELCOME|alice\n");
        String line;
        boolean joined = false;
        boolean finished = false;
        int round = 0;
        // Scores below are scripted server fixtures, never client-side scoring.
        while ((line = input.readLine()) != null) {
            if (line.equals("LIST_ROOMS")) {
                FakeGameServer.write(socket, "ROOMS|r1:Networks:1:" + (finished ? "FINISHED" : "LOBBY") + "\n");
            } else if (line.equals("PING")) {
                FakeGameServer.write(socket, "PONG\n");
            } else if (line.startsWith("CREATE_ROOM|") || line.equals("JOIN|r1")) {
                joined = true;
                FakeGameServer.write(socket, "JOINED|r1|Networks|true\nPLAYER_JOINED|bob\n");
            } else if (line.equals("START") && joined && !finished) {
                round = 1; FakeGameServer.write(socket, question(1));
            } else if (line.startsWith("ANSWER|q1|") && round == 1) {
                FakeGameServer.write(socket, "RESULT|q1|1|130|130\n" + question(2)); round = 2;
            } else if (line.startsWith("ANSWER|q2|") && round == 2) {
                FakeGameServer.write(socket, "RESULT|q2|0|100|230\nGAME_OVER|alice|alice:230;bob:90\n");
                round = 0; finished = true;
            } else if (line.equals("LEAVE")) {
                joined = false;
            } else {
                FakeGameServer.write(socket, "ERROR|BAD_MESSAGE|Mock script cannot perform that action\n");
            }
        }
    }

    static String question(int round) {
        return round == 1
                ? "QUESTION|q1|1|2|Which transport provides reliable ordered delivery?|UDP|TCP|ICMP|ARP|15\n"
                : "QUESTION|q2|2|2|Which service translates hostnames to addresses?|DNS|DHCP|NTP|FTP|15\n";
    }

    private static void expect(BufferedReader input, String expected) throws IOException {
        if (!expected.equals(input.readLine())) { throw new IOException("Unexpected mock demo handshake."); }
    }
}
