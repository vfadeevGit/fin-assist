package ru.stnovator.finassist.ai.schedule;

import io.jmix.aitools.dataload.execution.JpqlExecutionParameter;
import io.jmix.aitools.dataload.execution.JpqlExecutionRequest;
import io.jmix.aitools.dataload.execution.JpqlExecutionResult;
import io.jmix.aitools.dataload.tool.JpqlExecutorTool;
import io.jmix.aitoolsflowuidata.entity.AiConversationEntity;
import io.jmix.data.PersistenceHints;
import io.jmix.core.SaveContext;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.AccessDeniedException;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.entity.AiScheduleImport;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.entity.Customer;
import ru.stnovator.finassist.entity.User;
import ru.stnovator.finassist.test_support.DashboardFixtures;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the same native execution pipeline used by ordinary AI data questions. */
@SpringBootTest
class ScheduleNativeJpqlSecurityTest {
    @Autowired JpqlExecutorTool executor;
    @Autowired UnconstrainedDataManager data;
    @Autowired SystemAuthenticator auth;

    private Customer customer;
    private Contract contract;
    private AiConversationEntity conversation;
    private AiScheduleImport receipt;
    private final String owner = "native-jpql-" + UUID.randomUUID();
    private final String contractNumber = "jpql-" + UUID.randomUUID();

    @BeforeEach
    void setup() {
        auth.begin("admin");
        customer = data.create(Customer.class);
        customer.setName("НК НПЗ " + owner);
        customer = data.save(customer);
        contract = data.create(Contract.class);
        contract.setInternalID(contractNumber);
        contract.setStartDate(LocalDate.of(2026, 1, 1));
        contract.setEndDate(LocalDate.of(2026, 12, 31));
        contract.setCustomer(customer);
        contract = data.save(contract);
        conversation = data.create(AiConversationEntity.class);
        conversation.setUsername(owner);
        conversation.setTitle("JPQL regression");
        conversation = data.save(conversation);
        receipt = data.create(AiScheduleImport.class);
        receipt.setOwner(owner);
        receipt.setConversation(conversation);
        receipt.setOriginalConversationId(conversation.getId());
        receipt.setContentJson("{\"secret\":\"foreign-owner-content\"}");
        receipt = data.save(receipt);
        // Full resource access must still respect mandatory technical-data boundaries.
        User admin = data.load(User.class).query("select u from User u where u.username = 'admin'").one();
        DashboardFixtures.authenticate(admin, "ROLE_system-full-access");
    }

    @AfterEach
    void cleanup() {
        if (receipt != null) data.load(AiScheduleImport.class).id(receipt.getId()).optional().ifPresent(data::remove);
        if (conversation != null) data.load(AiConversationEntity.class).id(conversation.getId()).optional().ifPresent(data::remove);
        if (contract != null) data.load(Contract.class).id(contract.getId()).optional()
                .ifPresent(row -> data.save(new SaveContext().removing(row)
                        .setHint(PersistenceHints.SOFT_DELETION, false)));
        if (customer != null) data.load(Customer.class).id(customer.getId()).optional()
                .ifPresent(row -> data.save(new SaveContext().removing(row)
                        .setHint(PersistenceHints.SOFT_DELETION, false)));
        auth.end();
    }

    @ParameterizedTest
    @ValueSource(strings = {"join", "left join"})
    void businessJoinReturnsMatchingContracts(String join) {
        var result = execute("select c.internalID as contractNumber, customer.name as customerName "
                + "from Contract c " + join + " c.customer customer "
                + "where customer.name = :customerName", List.of("contractNumber", "customerName"),
                List.of(new JpqlExecutionParameter("customerName", "String", customer.getName())));

        assertExecuted(result);
        assertThat(result.getRows()).containsExactly(Map.of("contractNumber", contractNumber,
                "customerName", customer.getName()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"join", "left join"})
    void businessAggregateReturnsMatchingContractCount(String join) {
        var result = execute("select count(c) as contractCount from Contract c " + join
                + " c.customer customer where customer.name = :customerName", List.of("contractCount"),
                List.of(new JpqlExecutionParameter("customerName", "String", customer.getName())));

        assertExecuted(result);
        assertThat(result.getRows()).containsExactly(Map.of("contractCount", 1L));
    }

    @Test
    void originalCyrillicSearchTermIsFoldedByTheDatabaseOnBothSides() {
        var result = execute("select c.internalID as contractNumber from Contract c "
                + "join c.customer customer where lower(customer.name) like lower(:customerTerm) "
                + "and c.internalID = :contractNumber", List.of("contractNumber"), List.of(
                new JpqlExecutionParameter("customerTerm", "String", "%НК НПЗ%"),
                new JpqlExecutionParameter("contractNumber", "String", contractNumber)));

        assertExecuted(result);
        assertThat(result.getRows()).containsExactly(Map.of("contractNumber", contractNumber));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select i.contentJson as secretValue from AiScheduleImport i",
            "select a.fileName as secretValue from AiScheduleAttachment a",
            "select a.owner as secretValue from AiScheduleImport i join i.attachment a",
            "select a.owner as secretValue from AiScheduleImport i left join i.attachment a",
            "select c.internalID as secretValue from Contract c, AiScheduleImport i where i.contentJson like '%foreign-owner-content%'",
            "select c.internalID as secretValue from Contract c where exists (select i.id from AiScheduleImport i where i.contentJson like '%foreign-owner-content%')",
            "select c.internalID as secretValue from Contract c where exists (select a.id from AiScheduleAttachment a where a.fileName like '%secret%')"
    })
    void technicalEntityQueriesAreDeniedEvenWithFullResourceAccess(String jpql) {
        assertThatThrownBy(() -> execute(jpql, List.of("secretValue"), List.of()))
                .isInstanceOf(AccessDeniedException.class);
    }

    private JpqlExecutionResult execute(String jpql, List<String> columns, List<JpqlExecutionParameter> parameters) {
        return executor.executeQuery(new JpqlExecutionRequest("Покажи мне все контракты по НК НПЗ",
                jpql, parameters, columns, 100, 0), new ToolContext(Map.of()));
    }

    private void assertExecuted(JpqlExecutionResult result) {
        assertThat(result.getValidationResult().isValid()).as("valid JPQL: %s", result.getValidationResult().getIssues()).isTrue();
        assertThat(result.getExecutionError()).isNull();
        assertThat(result.isExecuted()).isTrue();
    }
}
