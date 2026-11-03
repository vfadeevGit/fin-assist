package ru.stnovator.finassist.service;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class ContractTermsMigrationTest {
    @Autowired DataSource dataSource;

    @Test void upgradesLegacyAddendumWithoutInventingAdjustments() throws Exception {
        withLegacySchema(false, (jdbc, migration) -> {
            migration.afterPropertiesSet();
            var row = jdbc.queryForMap("select amount_adjustment, new_end_date from addendum");
            assertThat(row.get("amount_adjustment")).isNull();
            assertThat(row.get("new_end_date")).isNull();
            assertThat(jdbc.queryForObject("select sum_total from contract", java.math.BigDecimal.class))
                    .isEqualByComparingTo("100");
        });
    }

    @Test void duplicateLegacyDatesHaltMigrationWithoutChangingData() throws Exception {
        withLegacySchema(true, (jdbc, migration) -> {
            assertThatThrownBy(migration::afterPropertiesSet).hasStackTraceContaining("Resolve duplicate active addendum dates");
            assertThat(jdbc.queryForObject("select count(*) from addendum", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = current_schema() and table_name = 'addendum' and column_name = 'amount_adjustment'", Integer.class)).isZero();
        });
    }

    private void withLegacySchema(boolean duplicate, MigrationCheck check) throws Exception {
        String schema = "terms_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection()) {
            String originalSchema = connection.getSchema();
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("create schema " + schema);
            try {
                connection.setSchema(schema);
                jdbc.execute("create table contract (id uuid primary key, sum_total decimal(19,2), deleted_date timestamp)");
                jdbc.execute("create table addendum (id uuid primary key, contract_id uuid, effective_date date, deleted_date timestamp)");
                UUID contractId = UUID.randomUUID();
                jdbc.update("insert into contract (id, sum_total) values (?, 100)", contractId);
                jdbc.update("insert into addendum (id, contract_id, effective_date) values (?, ?, date '2026-01-01')", UUID.randomUUID(), contractId);
                if (duplicate) jdbc.update("insert into addendum (id, contract_id, effective_date) values (?, ?, date '2026-01-01')", UUID.randomUUID(), contractId);
                SpringLiquibase migration = new SpringLiquibase();
                migration.setDataSource(new SingleConnectionDataSource(connection, true));
                migration.setDefaultSchema(schema);
                migration.setLiquibaseSchema(schema);
                migration.setChangeLog("classpath:ru/stnovator/finassist/liquibase/changelog/2026/11/03-000100-addendum-contract-terms.xml");
                check.run(jdbc, migration);
            } finally {
                connection.setSchema(originalSchema);
                jdbc.execute("drop schema " + schema + " cascade");
            }
        }
    }

    @FunctionalInterface
    private interface MigrationCheck {
        void run(JdbcTemplate jdbc, SpringLiquibase migration) throws Exception;
    }
}
