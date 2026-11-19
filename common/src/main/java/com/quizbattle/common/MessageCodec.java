package com.quizbattle.common;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Codec for encoding and decoding protocol messages.
 * 
 * Wire format: UTF-8, one message per line (terminated by \n), fields separated by |.
 * Maximum 4096 bytes per line.
 * 
 * Escaping inside fields:
 * - \\ for a backslash
 * - \| for a pipe
 * - \n for a newline character
 */
public final class MessageCodec {
    
    private static final int MAX_LINE_BYTES = 4096;
    private static final char FIELD_SEPARATOR = '|';
    
    private MessageCodec() {
        // Utility class
    }
    
    /**
     * Encodes a message to a string without trailing newline.
     * 
     * @param message the message to encode
     * @return the encoded string
     * @throws ProtocolException if the encoded message exceeds 4096 bytes
     */
    public static String encode(Message message) {
        StringBuilder sb = new StringBuilder();
        sb.append(message.type().name());
        
        for (String field : message.fields()) {
            sb.append(FIELD_SEPARATOR);
            sb.append(escapeField(field));
        }
        
        String encoded = sb.toString();
        byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
        
        if (bytes.length > MAX_LINE_BYTES) {
            throw new ProtocolException("Encoded message exceeds maximum size of " + MAX_LINE_BYTES + " bytes (was " + bytes.length + ")");
        }
        
        return encoded;
    }
    
    /**
     * Decodes a line into a Message.
     * 
     * @param line the line to decode (without trailing newline)
     * @return the decoded message
     * @throws ProtocolException if the line is malformed
     */
    public static Message decode(String line) throws ProtocolException {
        if (line == null || line.isEmpty()) {
            throw new ProtocolException("Line cannot be null or empty");
        }
        
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_LINE_BYTES) {
            throw new ProtocolException("Line exceeds maximum size of " + MAX_LINE_BYTES + " bytes");
        }
        
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaping = false;
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            
            if (escaping) {
                switch (c) {
                    case '\\':
                        current.append('\\');
                        break;
                    case '|':
                        current.append('|');
                        break;
                    case 'n':
                        current.append('\n');
                        break;
                    default:
                        // Invalid escape sequence - treat as literal backslash + char
                        current.append('\\').append(c);
                }
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else if (c == FIELD_SEPARATOR) {
                parts.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        
        // Add the last field
        parts.add(current.toString());
        
        if (parts.isEmpty()) {
            throw new ProtocolException("No fields in message");
        }
        
        String typeStr = parts.get(0);
        MessageType type;
        try {
            type = MessageType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Unknown message type: " + typeStr);
        }
        
        List<String> fields = parts.subList(1, parts.size());
        return new Message(type, fields);
    }
    
    /**
     * Escapes a field value for wire transmission.
     */
    private static String escapeField(String field) {
        if (field == null) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder(field.length() * 2);
        
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '|':
                    sb.append("\\|");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                default:
                    sb.append(c);
            }
        }
        
        return sb.toString();
    }
}
