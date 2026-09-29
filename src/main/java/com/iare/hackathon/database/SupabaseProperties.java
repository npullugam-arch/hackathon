package com.iare.hackathon.database;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.supabase")
public record SupabaseProperties(boolean enabled, String jdbcUrl, String host, Integer port,
        String database, String username, String password, String sslMode, String sslRootCert,
        int maximumPoolSize, long connectionTimeoutMs, int connectTimeoutSeconds, int socketTimeoutSeconds) {
    public void validate() {
        if (blank(username) || blank(password))
            throw new IllegalArgumentException("Set app.supabase.username and app.supabase.password before enabling Supabase.");
        if (blank(jdbcUrl)) {
            if (blank(host) || blank(database) || port == null || port < 1 || port > 65535)
                throw new IllegalArgumentException("Set app.supabase.host, port and database, or supply a PostgreSQL JDBC URL.");
            if (!host.matches("[a-zA-Z0-9.\\-]+") || !database.matches("[a-zA-Z0-9_\\-]+"))
                throw new IllegalArgumentException("Supabase host/database must contain only hostname/database characters; use a DNS hostname.");
        } else if (!jdbcUrl.matches("jdbc:postgresql://[^/?@]+/[^?/#]+")) {
            throw new IllegalArgumentException("app.supabase.jdbc-url must be jdbc:postgresql://HOST:PORT/DATABASE without credentials or query parameters; use the separate connection properties.");
        }
        if (!java.util.Set.of("require", "verify-ca", "verify-full").contains(sslMode == null ? "" : sslMode))
            throw new IllegalArgumentException("app.supabase.ssl-mode must be require, verify-ca or verify-full.");
        if (maximumPoolSize < 1 || maximumPoolSize > 50 || connectionTimeoutMs < 1000
                || connectTimeoutSeconds < 1 || socketTimeoutSeconds < 1)
            throw new IllegalArgumentException("Supabase pool size must be 1–50 and connection/socket timeouts must be positive (connection-timeout-ms >= 1000).");
    }
    public String resolvedJdbcUrl() {
        return blank(jdbcUrl) ? "jdbc:postgresql://" + host + ":" + port + "/" + database : jdbcUrl;
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    @Override public String toString() { return "SupabaseProperties[enabled=" + enabled + ", credentials=REDACTED]"; }
}
