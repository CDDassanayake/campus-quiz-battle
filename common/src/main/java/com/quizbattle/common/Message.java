package com.quizbattle.common;

import java.util.List;
import java.util.Objects;

/**
 * A protocol message with a type and a list of string fields.
 * Fields are separated by | in the wire format.
 */
public record Message(MessageType type, List<String> fields) {
    
    public Message {
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(fields, "fields cannot be null");
    }
    
    /**
     * Returns the field at the given index, or throws if missing.
     */
    public String getField(int index) {
        if (index < 0 || index >= fields.size()) {
            throw new IllegalArgumentException("Field index " + index + " out of bounds (size: " + fields.size() + ")");
        }
        return fields.get(index);
    }
    
    /**
     * Returns the field at the given index, or a default value if missing.
     */
    public String getFieldOrDefault(int index, String defaultValue) {
        if (index < 0 || index >= fields.size()) {
            return defaultValue;
        }
        return fields.get(index);
    }
    
    /**
     * Returns the number of fields.
     */
    public int fieldCount() {
        return fields.size();
    }
}
