package com.quizbattle.common;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Application configuration loaded from properties files.
 * Environment variables override properties file values.
 * 
 * Run from the repository root to load config/server.properties or config/client.properties.
 */
public final class AppConfig {
    
    private final Properties properties;
    
    private AppConfig(Properties properties) {
        this.properties = new Properties();
        this.properties.putAll(properties);
    }
    
    /**
     * Loads configuration from a properties file relative to the repository root.
     * Environment variables override properties file values.
     * 
     * @param configPath the path to the config file (e.g., "config/server.properties")
     * @return the loaded configuration
     * @throws IOException if the file cannot be read
     */
    public static AppConfig load(String configPath) throws IOException {
        Properties props = new Properties();
        
        // Try to load from the given path
        Path path = Paths.get(configPath);
        if (Files.exists(path)) {
            try (InputStream is = new FileInputStream(path.toFile())) {
                props.load(is);
            }
        } else {
            // Try as a classpath resource
            try (InputStream is = AppConfig.class.getClassLoader().getResourceAsStream(configPath)) {
                if (is != null) {
                    props.load(is);
                }
            }
        }
        
        // Override with environment variables
        // Environment variable format: QB_<property name with underscores>
        // Example: QB_GATEWAY_PORT overrides gateway.port
        for (String envName : System.getenv().keySet()) {
            if (envName.startsWith("QB_")) {
                String propName = envName.substring(3).toLowerCase().replace('_', '.');
                props.setProperty(propName, System.getenv(envName));
            }
        }
        
        return new AppConfig(props);
    }
    
    /**
     * Gets a string property.
     * 
     * @param key the property key
     * @return the property value, or null if not found
     */
    public String getString(String key) {
        return properties.getProperty(key);
    }
    
    /**
     * Gets a string property with a default value.
     * 
     * @param key the property key
     * @param defaultValue the default value if not found
     * @return the property value, or the default
     */
    public String getString(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }
    
    /**
     * Gets an integer property.
     * 
     * @param key the property key
     * @return the property value
     * @throws IllegalArgumentException if the property is not found or not a valid integer
     */
    public int getInt(String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Property not found: " + key);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property '" + key + "' is not a valid integer: " + value);
        }
    }
    
    /**
     * Gets an integer property with a default value.
     * 
     * @param key the property key
     * @param defaultValue the default value if not found
     * @return the property value, or the default
     */
    public int getInt(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    /**
     * Gets a boolean property.
     * 
     * @param key the property key
     * @return the property value
     * @throws IllegalArgumentException if the property is not found
     */
    public boolean getBoolean(String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Property not found: " + key);
        }
        return Boolean.parseBoolean(value);
    }
    
    /**
     * Gets a boolean property with a default value.
     * 
     * @param key the property key
     * @param defaultValue the default value if not found
     * @return the property value, or the default
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }
    
    /**
     * Checks if a property exists.
     * 
     * @param key the property key
     * @return true if the property exists
     */
    public boolean hasKey(String key) {
        return properties.containsKey(key);
    }
}
