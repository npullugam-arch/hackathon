package com.iare.hackathon.database;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupabaseConfigurationTests {
    private SupabaseProperties properties(String url, String host, String password, String ssl) {
        return new SupabaseProperties(true, url, host, 5432, "postgres", "test-user", password,
                ssl, "", 2, 1000, 1, 1);
    }
    @Test
    void buildsJdbcUrlFromSeparateFields() {
        var config = properties("", "example.pooler.supabase.com", "test-only", "require");
        assertDoesNotThrow(config::validate);
        assertEquals("jdbc:postgresql://example.pooler.supabase.com:5432/postgres", config.resolvedJdbcUrl());
        assertFalse(config.toString().contains("test-only"));
    }
    @Test
    void explicitJdbcUrlTakesPrecedence() {
        var config = properties("jdbc:postgresql://other.example:5432/postgres", "", "test-only", "verify-full");
        assertDoesNotThrow(config::validate);
        assertEquals("jdbc:postgresql://other.example:5432/postgres", config.resolvedJdbcUrl());
    }
    @Test
    void missingCredentialsAndUnsafeUrlOrSslFailBeforeConnecting() {
        assertThrows(IllegalArgumentException.class, () -> properties("", "example.com", "", "require").validate());
        assertThrows(IllegalArgumentException.class, () -> properties("", "example.com", "test-only", "disable").validate());
        assertThrows(IllegalArgumentException.class, () -> properties("jdbc:postgresql://example.com/db?password=secret", "", "test-only", "require").validate());
    }
    @Test
    void unreachableDatabaseFailsStartupWithSafeDiagnostic() {
        var config = new SupabaseProperties(true, "", "127.0.0.1", 1, "postgres", "test-user", "test-only",
                "require", "", 1, 1000, 1, 1);
        var error = assertThrows(IllegalStateException.class, () -> new SupabaseConfiguration().supabaseDataSource(config));
        assertTrue(error.getMessage().contains("Supabase initialization failed"));
        assertFalse(error.getMessage().contains("test-only"));
        assertNull(error.getCause());
    }
}
