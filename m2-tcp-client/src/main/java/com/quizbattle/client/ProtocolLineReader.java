package com.quizbattle.client;

import com.quizbattle.common.Message;
import com.quizbattle.common.MessageCodec;
import com.quizbattle.common.ProtocolException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounded framing only; shared MessageCodec remains the message codec. */
final class ProtocolLineReader {
    private final InputStream input;

    ProtocolLineReader(InputStream input) {
        this.input = input;
    }

    Message read(Socket socket, long deadlineNanos) throws IOException {
        byte[] bytes = new byte[4096];
        int length = 0;
        while (true) {
            // Authentication has a total deadline, so a trickle of bytes cannot
            // extend it indefinitely. Gameplay uses the configured SO_TIMEOUT.
            if (deadlineNanos != 0) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    throw new SocketTimeoutException();
                }
                socket.setSoTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE,
                        (remaining + 999_999) / 1_000_000)));
            }
            int next = input.read();
            if (next == -1) {
                if (length == 0) {
                    return null;
                }
                throw new IOException("Server closed an incomplete protocol message.");
            }
            if (next == '\n') {
                break;
            }
            if (length == bytes.length) {
                throw new IOException("Incoming message exceeds the 4096-byte limit.");
            }
            bytes[length++] = (byte) next;
        }
        String line;
        try {
            line = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Server sent invalid UTF-8.");
        }
        // The shared decoder is permissive about unknown/trailing escapes.
        // Validate framing syntax here without changing that frozen contract.
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\r') {
                throw new IOException("Server sent a malformed protocol message.");
            }
            if (c == '\\') {
                if (++i == line.length() || "\\|n".indexOf(line.charAt(i)) < 0) {
                    throw new IOException("Server sent an invalid protocol escape.");
                }
            }
        }
        try {
            Message decoded = MessageCodec.decode(line);
            return new Message(decoded.type(), java.util.List.copyOf(decoded.fields()));
        } catch (ProtocolException e) {
            // Do not retain raw server content in exception messages/causes.
            throw new IOException("Server sent a malformed protocol message.");
        }
    }
}
