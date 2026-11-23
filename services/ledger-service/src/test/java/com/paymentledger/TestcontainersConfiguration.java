package com.paymentledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Postgres in a throwaway Docker container. No database mocks: Flyway migrations,
 * constraints, triggers and grants are all tested for real.
 *
 * <p>Like production, Flyway runs as the owner (here the container's superuser) and the
 * application runs as {@code ledger_app}, which only has the privileges the migrations grant.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final String APP_ROLE = "ledger_app";
    static final String APP_PASSWORD = "ledger-app-test";

    @Bean
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine")).withInitScript("db/test-roles.sql");
    }

    @Bean
    DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", () -> APP_ROLE);
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
            registry.add("spring.flyway.user", postgres::getUsername);
            registry.add("spring.flyway.password", postgres::getPassword);
            registry.add("spring.flyway.placeholders.appRole", () -> APP_ROLE);
        };
    }

    @Bean
    Superuser superuser(PostgreSQLContainer postgres) {
        return new Superuser(postgres);
    }
}
