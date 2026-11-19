package ru.stnovator.finassist.ai.schedule;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ScheduleConfirmationMigrationTest {
    @Autowired DataSource source;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void installsTablesWithoutPromotingExistingDrafts(boolean legacy) throws Exception {
        String schema = "confirmation_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = source.getConnection()) {
            String original = connection.getSchema();
            var isolated = new SingleConnectionDataSource(connection, true);
            var jdbc = new JdbcTemplate(isolated);
            jdbc.execute("create schema " + schema);
            try {
                connection.setSchema(schema);
                jdbc.execute("create table aitls_ai_chat_message_entity(id uuid primary key)");
                jdbc.execute("create table aitls_ai_conversation_entity(id uuid primary key)");
                jdbc.execute("create table ai_schedule_import(id uuid primary key, import_status text, result_id uuid)");
                UUID resultId = UUID.randomUUID();
                if (legacy) {
                    jdbc.update("insert into ai_schedule_import values (?, 'PREVIEW_READY', null), (?, 'SAVED', ?)",
                            UUID.randomUUID(), UUID.randomUUID(), resultId);
                }
                // Exercise the original W1 schema first, including its recorded Liquibase checksum.
                var migration = new SpringLiquibase() {
                    @Override
                    protected void performUpdate(liquibase.Liquibase liquibase) throws liquibase.exception.LiquibaseException {
                        liquibase.update(1, new liquibase.Contexts(), new liquibase.LabelExpression());
                    }
                };
                migration.setDataSource(isolated);
                migration.setDefaultSchema(schema);
                migration.setLiquibaseSchema(schema);
                migration.setChangeLog("classpath:ru/stnovator/finassist/liquibase/changelog/2026/11/19-000100-chat-confirmation.xml");
                migration.afterPropertiesSet();
                assertThat(jdbc.queryForObject("select count(*) from ai_schedule_proposal", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from ai_schedule_reply", Integer.class)).isZero();
                UUID operation = jdbc.query("select id from ai_schedule_import where import_status='SAVED'",
                        rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
                UUID user = UUID.randomUUID();
                if (legacy) {
                    UUID proposal = UUID.randomUUID();
                    UUID conversation = UUID.randomUUID();
                    jdbc.update("insert into aitls_ai_conversation_entity values (?)", conversation);
                    jdbc.update("insert into aitls_ai_chat_message_entity values (?), (?)", proposal, user);
                    jdbc.update("insert into ai_schedule_proposal values(?,?,?,?,?,false)", proposal, conversation, operation, 5, "digest");
                    jdbc.update("insert into ai_schedule_reply values(?,?,?,?)", user, proposal, 5, "APPLIED");
                }
                migration.afterPropertiesSet();
                if (legacy) {
                    assertThat(jdbc.queryForObject("select applied_operation_id from ai_schedule_reply where message_id=?", UUID.class, user))
                            .isEqualTo(operation);
                    assertThat(jdbc.queryForObject("select count(*) from ai_schedule_import", Integer.class)).isEqualTo(2);
                    assertThat(jdbc.queryForObject("select result_id from ai_schedule_import where import_status='SAVED'", UUID.class)).isEqualTo(resultId);
                }
            } finally {
                connection.setSchema(original);
                jdbc.execute("drop schema " + schema + " cascade");
            }
        }
    }
}
