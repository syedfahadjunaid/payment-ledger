package com.paymentledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A database superuser, for tests that look (or tamper) behind the application's back.
 * Not a DataSource bean on purpose: a second DataSource would confuse Spring Boot's auto-configuration.
 */
public final class Superuser {

    private final PostgreSQLContainer postgres;
    private JdbcClient jdbc;

    Superuser(PostgreSQLContainer postgres) {
        this.postgres = postgres;
    }

    /** Auto-commit JDBC as the superuser. Table names need the {@code ledger.} schema prefix. */
    public synchronized JdbcClient jdbc() {
        if (jdbc == null) {
            jdbc = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword()));
        }
        return jdbc;
    }

    /**
     * Runs one statement with all triggers switched off for this session
     * ({@code session_replication_role = replica}, which only a superuser may set). This is how a
     * careless DBA could corrupt a balance; the reconciliation job must notice.
     */
    public void runWithoutTriggers(String sql, Object... params) {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
             Statement setup = connection.createStatement()) {
            setup.execute("SET session_replication_role = replica");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    statement.setObject(i + 1, params[i]);
                }
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
