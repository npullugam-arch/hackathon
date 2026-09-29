package com.iare.hackathon.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableConfigurationProperties(SupabaseProperties.class)
public class SupabaseConfiguration {
    private static final Logger log = LoggerFactory.getLogger(SupabaseConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.supabase.enabled", havingValue = "true")
    HikariDataSource supabaseDataSource(SupabaseProperties properties) {
        properties.validate();
        var config = new HikariConfig();
        config.setPoolName("supabase-pool");
        config.setJdbcUrl(properties.resolvedJdbcUrl());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(properties.maximumPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(properties.connectionTimeoutMs());
        config.setValidationTimeout(Math.min(5000, properties.connectionTimeoutMs()));
        config.setInitializationFailTimeout(-1); // Explicit startup probe below provides a safe diagnostic.
        config.addDataSourceProperty("sslmode", properties.sslMode());
        if (properties.sslRootCert() != null && !properties.sslRootCert().isBlank())
            config.addDataSourceProperty("sslrootcert", properties.sslRootCert());
        config.addDataSourceProperty("connectTimeout", properties.connectTimeoutSeconds());
        config.addDataSourceProperty("socketTimeout", properties.socketTimeoutSeconds());
        config.addDataSourceProperty("ApplicationName", "hackathon");
        var dataSource = new HikariDataSource(config);
        try {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.setQueryTimeout(properties.connectTimeoutSeconds());
                try (var result = statement.executeQuery("SELECT 1")) {
                    if (!result.next() || result.getInt(1) != 1) throw new SQLException("Connection probe failed");
                }
            }
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .schemas("app_private").defaultSchema("app_private").createSchemas(true)
                    .cleanDisabled(true).load().migrate();
            log.info("Supabase PostgreSQL connection verified; application database migrations are current.");
            return dataSource;
        } catch (Exception ex) {
            dataSource.close();
            // Never include connection strings, usernames or passwords in the startup error.
            String state = ex instanceof SQLException sql && sql.getSQLState() != null ? sql.getSQLState() : "unavailable";
            log.error("Supabase initialization failed (SQL state {}). Check connection settings, network/SSL access and schema migration permissions.", state);
            throw new IllegalStateException("Supabase initialization failed. Verify app.supabase settings, database reachability, SSL certificate and CREATE privileges for schema app_private. No database-backed login will be accepted.");
        }
    }

    @Bean
    @ConditionalOnProperty(name = "app.supabase.enabled", havingValue = "true")
    JdbcTemplate supabaseJdbcTemplate(HikariDataSource supabaseDataSource, SupabaseProperties properties) {
        var jdbc = new JdbcTemplate(supabaseDataSource);
        jdbc.setQueryTimeout(properties.socketTimeoutSeconds());
        return jdbc;
    }
}
