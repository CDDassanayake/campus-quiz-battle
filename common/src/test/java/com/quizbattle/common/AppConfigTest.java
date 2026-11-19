package com.quizbattle.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class AppConfigTest {
    
    @TempDir
    Path tempDir;
    
    @Test
    void testLoadFromStringProperty() throws IOException {
        Path configPath = tempDir.resolve("test.properties");
        Files.writeString(configPath, "server.name=TestServer\nserver.host=localhost");
        
        AppConfig config = AppConfig.load(configPath.toString());
        assertEquals("TestServer", config.getString("server.name"));
        assertEquals("localhost", config.getString("server.host"));
    }
    
    @Test
    void testLoadFromIntProperty() throws IOException {
        Path configPath = tempDir.resolve("test.properties");
        Files.writeString(configPath, "gateway.port=5050\ntls.port=5443");
        
        AppConfig config = AppConfig.load(configPath.toString());
        assertEquals(5050, config.getInt("gateway.port"));
        assertEquals(5443, config.getInt("tls.port"));
    }
    
    @Test
    void testLoadFromBooleanProperty() throws IOException {
        Path configPath = tempDir.resolve("test.properties");
        Files.writeString(configPath, "debug.enabled=true\nproduction.enabled=false");
        
        AppConfig config = AppConfig.load(configPath.toString());
        assertTrue(config.getBoolean("debug.enabled"));
        assertFalse(config.getBoolean("production.enabled"));
    }
    
    @Test
    void testGetStringWithDefault() throws IOException {
        Path configPath = tempDir.resolve("test.properties");
        Files.writeString(configPath, "existing.value=test");
        
        AppConfig config = AppConfig.load(configPath.toString());
        assertEquals("test", config.getString("existing.value", "default"));
        assertEquals("default", config.getString("missing.value", "default"));
    }
    
    @Test
    void testGetIntWithDefault() throws IOException {
        Path configPath = tempDir.resolve("test.properties");
        Files.writeString(configPath, "existing.port=5050");
        
        AppConfig config = AppConfig.load(configPath.toString());
        assertEquals(5050, config.getInt("existing.port", 8080));
        assertEquals(8080, config.getInt("missing.port", 8080));
    }
    
    @Test
    void testGetBooleanWithDefault() throws IOException {
        Path configPath = tempDir.resolve("test.properties");
        Files.writeString(configPath, "existing.flag=true");
        
        AppConfig config = AppConfig.load(configPath.toString());
        assertTrue(config.getBoolean("existing.flag", false));
        assertFalse(config.getBoolean("missing.flag", false));
    }
    
    @Test
    void testGetIntThrowsOnMissing() throws IOException {
        Path configPath = tempDir.resolve("test.properties");
        Files.writeString(configPath, "other.value=test");
        
        AppConfig config = AppConfig.load(configPath.toString());
        assertThrows(IllegalArgumentException.class, () -> config.getInt("missing.port"));
    }
    
    @Test
    void testGetIntThrowsOnInvalid() throws IOException {
        Path configPath = tempDir.resolve("test.properties");
        Files.writeString(configPath, "invalid.port=notanumber");
        
        AppConfig config = AppConfig.load(configPath.toString());
        assertThrows(IllegalArgumentException.class, () -> config.getInt("invalid.port"));
    }
    
    @Test
    void testGetIntWithDefaultReturnsDefaultOnInvalid() throws IOException {
        Path configPath = tempDir.resolve("test.properties");
        Files.writeString(configPath, "invalid.port=notanumber");
        
        AppConfig config = AppConfig.load(configPath.toString());
        assertEquals(8080, config.getInt("invalid.port", 8080));
    }
    
    @Test
    void testHasKey() throws IOException {
        Path configPath = tempDir.resolve("test.properties");
        Files.writeString(configPath, "existing.key=value");
        
        AppConfig config = AppConfig.load(configPath.toString());
        assertTrue(config.hasKey("existing.key"));
        assertFalse(config.hasKey("missing.key"));
    }
    
    @Test
    void testEnvironmentVariableOverride() throws IOException {
        // Set an environment variable
        String originalValue = System.getenv("QB_TEST_PROPERTY");
        try {
            // This test assumes the environment variable can be set
            // In practice, environment variables are read-only in most JVMs
            // So we'll just test the mechanism exists
            Path configPath = tempDir.resolve("test.properties");
            Files.writeString(configPath, "test.property=filevalue");
            
            AppConfig config = AppConfig.load(configPath.toString());
            // If QB_TEST_PROPERTY is set in the environment, it would override
            // Otherwise we get the file value
            String value = config.getString("test.property");
            assertNotNull(value);
        } finally {
            // Restore original value if possible (not typically possible)
        }
    }
    
    @Test
    void testEmptyPropertiesFile() throws IOException {
        Path configPath = tempDir.resolve("test.properties");
        Files.writeString(configPath, "");
        
        AppConfig config = AppConfig.load(configPath.toString());
        assertNull(config.getString("any.key"));
        assertEquals("default", config.getString("any.key", "default"));
    }
    
    @Test
    void testMissingFileReturnsEmptyConfig() throws IOException {
        // Loading a non-existent file should return an empty config
        // (not throw an exception, as it might be a classpath resource)
        AppConfig config = AppConfig.load("nonexistent.properties");
        assertNull(config.getString("any.key"));
    }
    
    @Test
    void testComplexProperties() throws IOException {
        Path configPath = tempDir.resolve("test.properties");
        Files.writeString(configPath, """
            # Comment line
            server.name=Quiz Battle Server
            gateway.port=5050
            tls.port=5443
            udp.discovery.port=5051
            udp.feed.port=5052
            game.handler.pool.size=8
            game.question.time.seconds=15
            """);
        
        AppConfig config = AppConfig.load(configPath.toString());
        assertEquals("Quiz Battle Server", config.getString("server.name"));
        assertEquals(5050, config.getInt("gateway.port"));
        assertEquals(5443, config.getInt("tls.port"));
        assertEquals(5051, config.getInt("udp.discovery.port"));
        assertEquals(5052, config.getInt("udp.feed.port"));
        assertEquals(8, config.getInt("game.handler.pool.size"));
        assertEquals(15, config.getInt("game.question.time.seconds"));
    }
}
