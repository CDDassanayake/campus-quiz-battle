package com.quizbattle.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class MessageCodecTest {
    
    @Test
    void testEncodeSimpleMessage() {
        Message msg = new Message(MessageType.HELLO, List.of("token123"));
        String encoded = MessageCodec.encode(msg);
        assertEquals("HELLO|token123", encoded);
    }
    
    @Test
    void testDecodeSimpleMessage() {
        Message decoded = MessageCodec.decode("HELLO|token123");
        assertEquals(MessageType.HELLO, decoded.type());
        assertEquals(List.of("token123"), decoded.fields());
    }
    
    @Test
    void testRoundTripSimpleMessage() {
        Message original = new Message(MessageType.HELLO, List.of("token123"));
        String encoded = MessageCodec.encode(original);
        Message decoded = MessageCodec.decode(encoded);
        assertEquals(original.type(), decoded.type());
        assertEquals(original.fields(), decoded.fields());
    }
    
    @Test
    void testEncodeWithPipeInField() {
        Message msg = new Message(MessageType.CREATE_ROOM, List.of("My|Room", "5"));
        String encoded = MessageCodec.encode(msg);
        assertEquals("CREATE_ROOM|My\\|Room|5", encoded);
    }
    
    @Test
    void testDecodeWithPipeInField() {
        Message decoded = MessageCodec.decode("CREATE_ROOM|My\\|Room|5");
        assertEquals(MessageType.CREATE_ROOM, decoded.type());
        assertEquals(List.of("My|Room", "5"), decoded.fields());
    }
    
    @Test
    void testRoundTripWithPipeInField() {
        Message original = new Message(MessageType.CREATE_ROOM, List.of("My|Room", "5"));
        String encoded = MessageCodec.encode(original);
        Message decoded = MessageCodec.decode(encoded);
        assertEquals(original.type(), decoded.type());
        assertEquals(original.fields(), decoded.fields());
    }
    
    @Test
    void testEncodeWithBackslashInField() {
        Message msg = new Message(MessageType.REGISTER, List.of("user\\name", "pass"));
        String encoded = MessageCodec.encode(msg);
        assertEquals("REGISTER|user\\\\name|pass", encoded);
    }
    
    @Test
    void testDecodeWithBackslashInField() {
        Message decoded = MessageCodec.decode("REGISTER|user\\\\name|pass");
        assertEquals(MessageType.REGISTER, decoded.type());
        assertEquals(List.of("user\\name", "pass"), decoded.fields());
    }
    
    @Test
    void testRoundTripWithBackslashInField() {
        Message original = new Message(MessageType.REGISTER, List.of("user\\name", "pass"));
        String encoded = MessageCodec.encode(original);
        Message decoded = MessageCodec.decode(encoded);
        assertEquals(original.type(), decoded.type());
        assertEquals(original.fields(), decoded.fields());
    }
    
    @Test
    void testEncodeWithNewlineInField() {
        Message msg = new Message(MessageType.QUESTION, List.of("Q1", "What\nis\nthis?"));
        String encoded = MessageCodec.encode(msg);
        assertEquals("QUESTION|Q1|What\\nis\\nthis?", encoded);
    }
    
    @Test
    void testDecodeWithNewlineInField() {
        Message decoded = MessageCodec.decode("QUESTION|Q1|What\\nis\\nthis?");
        assertEquals(MessageType.QUESTION, decoded.type());
        assertEquals(List.of("Q1", "What\nis\nthis?"), decoded.fields());
    }
    
    @Test
    void testRoundTripWithNewlineInField() {
        Message original = new Message(MessageType.QUESTION, List.of("Q1", "What\nis\nthis?"));
        String encoded = MessageCodec.encode(original);
        Message decoded = MessageCodec.decode(encoded);
        assertEquals(original.type(), decoded.type());
        assertEquals(original.fields(), decoded.fields());
    }
    
    @Test
    void testEncodeComplexEscaping() {
        Message msg = new Message(MessageType.QUESTION, List.of("Test\\|\\n", "A|B\\C"));
        String encoded = MessageCodec.encode(msg);
        assertEquals("QUESTION|Test\\\\\\|\\\\n|A\\|B\\\\C", encoded);
    }
    
    @Test
    void testDecodeComplexEscaping() {
        Message decoded = MessageCodec.decode("QUESTION|Test\\\\\\|\\\\n|A\\|B\\\\C");
        assertEquals(MessageType.QUESTION, decoded.type());
        assertEquals(List.of("Test\\|\n", "A|B\\C"), decoded.fields());
    }
    
    @Test
    void testRoundTripComplexEscaping() {
        Message original = new Message(MessageType.QUESTION, List.of("Test\\|\n", "A|B\\C"));
        String encoded = MessageCodec.encode(original);
        Message decoded = MessageCodec.decode(encoded);
        assertEquals(original.type(), decoded.type());
        assertEquals(original.fields(), decoded.fields());
    }
    
    @Test
    void testEncodeNoFields() {
        Message msg = new Message(MessageType.PING, List.of());
        String encoded = MessageCodec.encode(msg);
        assertEquals("PING", encoded);
    }
    
    @Test
    void testDecodeNoFields() {
        Message decoded = MessageCodec.decode("PING");
        assertEquals(MessageType.PING, decoded.type());
        assertTrue(decoded.fields().isEmpty());
    }
    
    @Test
    void testEncodeEmptyField() {
        Message msg = new Message(MessageType.REGISTER, List.of("", "password"));
        String encoded = MessageCodec.encode(msg);
        assertEquals("REGISTER||password", encoded);
    }
    
    @Test
    void testDecodeEmptyField() {
        Message decoded = MessageCodec.decode("REGISTER||password");
        assertEquals(MessageType.REGISTER, decoded.type());
        assertEquals(List.of("", "password"), decoded.fields());
    }
    
    @Test
    void testDecodeNullLineThrows() {
        assertThrows(ProtocolException.class, () -> MessageCodec.decode(null));
    }
    
    @Test
    void testDecodeEmptyLineThrows() {
        assertThrows(ProtocolException.class, () -> MessageCodec.decode(""));
    }
    
    @Test
    void testDecodeUnknownMessageTypeThrows() {
        assertThrows(ProtocolException.class, () -> MessageCodec.decode("UNKNOWN|field"));
    }
    
    @Test
    void testEncodeTooLongMessageThrows() {
        // Create a message that exceeds 4096 bytes
        StringBuilder longField = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            longField.append('a');
        }
        Message msg = new Message(MessageType.QUESTION, List.of(longField.toString()));
        assertThrows(ProtocolException.class, () -> MessageCodec.encode(msg));
    }
    
    @Test
    void testDecodeTooLongLineThrows() {
        StringBuilder longLine = new StringBuilder("HELLO|");
        for (int i = 0; i < 5000; i++) {
            longLine.append('a');
        }
        assertThrows(ProtocolException.class, () -> MessageCodec.decode(longLine.toString()));
    }
    
    @Test
    void testAllMessageTypesRoundTrip() {
        for (MessageType type : MessageType.values()) {
            Message msg = new Message(type, List.of("field1", "field2"));
            String encoded = MessageCodec.encode(msg);
            Message decoded = MessageCodec.decode(encoded);
            assertEquals(type, decoded.type(), "Failed for type: " + type);
            assertEquals(List.of("field1", "field2"), decoded.fields(), "Failed for type: " + type);
        }
    }
    
    @Test
    void testMessageFieldAccess() {
        Message msg = new Message(MessageType.HELLO, List.of("token", "extra"));
        assertEquals("token", msg.getField(0));
        assertEquals("extra", msg.getField(1));
        assertEquals(2, msg.fieldCount());
    }
    
    @Test
    void testMessageFieldAccessOutOfBounds() {
        Message msg = new Message(MessageType.HELLO, List.of("token"));
        assertThrows(IllegalArgumentException.class, () -> msg.getField(5));
        assertThrows(IllegalArgumentException.class, () -> msg.getField(-1));
    }
    
    @Test
    void testMessageFieldAccessWithDefault() {
        Message msg = new Message(MessageType.HELLO, List.of("token"));
        assertEquals("token", msg.getFieldOrDefault(0, "default"));
        assertEquals("default", msg.getFieldOrDefault(5, "default"));
        assertEquals("default", msg.getFieldOrDefault(-1, "default"));
    }
}
