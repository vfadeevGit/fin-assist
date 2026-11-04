package ru.stnovator.finassist.ai;

import io.jmix.aitools.tool.AiToolRegistry;
import io.jmix.core.*;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.view.ViewRegistry;
import io.jmix.aitools.dataload.introspection.AvailableEntityService;
import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.role.annotation.EntityPolicy;
import io.jmix.security.role.annotation.ResourceRole;
import io.jmix.securityflowui.role.annotation.ViewPolicy;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.stnovator.finassist.FinAssistApplication;
import ru.stnovator.finassist.entity.Customer;
import ru.stnovator.finassist.test_support.DashboardFixtures;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@UiTest
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class EntityLinksToolTest {
    @Autowired AiToolRegistry tools;
    @Autowired DataManager dataManager;
    @Autowired SystemAuthenticator authenticator;
    @Autowired EntityLinksTool links;
    @Autowired BusinessEntityRegistry registry;
    @Autowired Metadata metadata;
    @Autowired AccessManager accessManager;
    @Autowired ViewRegistry views;
    @Autowired Messages messages;
    @Autowired AvailableEntityService entities;
    private Authentication original;
    private Customer customer;

    @BeforeEach
    void setUp() {
        original = SecurityContextHolder.getContext().getAuthentication();
        authenticator.runWithUser("admin", () -> {
            customer = dataManager.create(Customer.class);
            customer.setName("Test customer [A]");
            customer = dataManager.save(customer);
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.getContext().setAuthentication(original);
        authenticator.runWithUser("admin", () -> dataManager.load(Id.of(customer))
                .hint(io.jmix.data.PersistenceHints.SOFT_DELETION, false).optional()
                .ifPresent(c -> dataManager.save(new SaveContext().removing(c)
                        .setHint(io.jmix.data.PersistenceHints.SOFT_DELETION, false))));
    }

    private String call(String name, String input) {
        var callback = tools.getAllCallbacks().stream()
                .filter(t -> t.getToolDefinition().name().equals(name)).findFirst();
        assertThat(callback).as("registered tool %s", name).isPresent();
        return callback.orElseThrow().call(input);
    }

    @Test
    void generatesRealDetailAndListRoutesThroughRegisteredCallbacks() {
        authenticator.runWithUser("admin", () -> {
            assertThat(call("finassist_getEntityLinks", "{\"entityName\":\"Customer\",\"ids\":[\"" + customer.getId() + "\"]}"))
                    .contains("/customers/" + customer.getId(), "Test customer");
            assertThat(call("finassist_getEntityListLink", "{\"entityName\":\"Customer\"}"))
                    .contains("/customers", "\"available\":true");
        });
    }

    @Test
    void generatedLinkNavigatesToTheRequestedRecord() {
        authenticator.runWithUser("admin", () -> {
            var link = links.getEntityLinks("Customer", List.of(customer.getId().toString())).getFirst();
            com.vaadin.flow.component.UI.getCurrent().navigate(link.url().substring(1));
            var view = (ru.stnovator.finassist.view.customer.CustomerDetailView)
                    io.jmix.flowui.testassist.UiTestUtils.getCurrentView();
            assertThat(view.getEditedEntity().getId()).isEqualTo(customer.getId());
        });
    }

    @Test
    void rejectsHiddenInvalidAndMissingRecordsWithoutDetails() {
        authenticator.runWithUser("admin", () -> {
            for (String entity : List.of("User", "Unknown", "Customer")) {
                assertThat(call("finassist_getEntityLinks", "{\"entityName\":\"" + entity + "\",\"ids\":[\"invalid\",\"" + UUID.randomUUID() + "\"]}"))
                        .doesNotContain("\"available\":true", "/customers/", "Test customer");
            }
        });
    }

    @Test
    void hidesDeletedAndViewDeniedRecords() {
        authenticator.runWithUser("admin", () -> {
            var user = dataManager.load(ru.stnovator.finassist.entity.User.class).query("e.username = 'admin'").one();
            DashboardFixtures.authenticate(user, "ROLE_finance-manager-role");
            assertThat(call("finassist_getEntityLinks", "{\"entityName\":\"Customer\",\"ids\":[\"" + customer.getId() + "\"]}"))
                    .doesNotContain("\"available\":true", "Test customer");
        });
        authenticator.runWithUser("admin", () -> {
            dataManager.remove(customer);
            assertThat(call("finassist_getEntityLinks", "{\"entityName\":\"Customer\",\"ids\":[\"" + customer.getId() + "\"]}"))
                    .doesNotContain("\"available\":true", "Test customer");
        });
    }

    @Test
    void enforcesEntityAndAttributePermissions() {
        authenticator.runWithUser("admin", () -> {
            var user = dataManager.load(ru.stnovator.finassist.entity.User.class).query("e.username = 'admin'").one();
            DashboardFixtures.authenticate(user);
            assertThat(links.getEntityListLink("Customer").available()).isFalse();
            assertThat(entities.findEntityDescriptorsByNames(List.of("Customer"))).isEmpty();
            DashboardFixtures.authenticate(user, "ROLE_ai-test-no-attributes");
            var result = links.getEntityLinks("Customer", List.of(customer.getId().toString())).getFirst();
            assertThat(result.available()).isTrue();
            assertThat(result.label()).doesNotContain("Test customer", customer.getId().toString());
            assertThat(entities.findEntityDescriptorsByNames(List.of("Customer")).getFirst().getProperties()).isEmpty();
        });
    }

    @Test
    void enforcesJpqlAndPredicateRowRestrictions() {
        try (var fixtures = new DashboardFixtures(dataManager, authenticator)) {
            for (String role : List.of(DashboardFixtures.JPQL, DashboardFixtures.PREDICATE)) {
                DashboardFixtures.restricted(fixtures.alice, role);
                assertThat(links.getEntityLinks("Customer", List.of(customer.getId().toString())).getFirst().available())
                        .as("Row role %s", role).isFalse();
            }
        }
    }

    @Test
    void boundsRequestsAndPreservesMixedResultOrder() {
        authenticator.runWithUser("admin", () -> {
            assertThat(links.getEntityLinks("Customer", Collections.nCopies(51, customer.getId().toString()))).isEmpty();
            assertThat(links.getEntityLinks("Customer", List.of())).isEmpty();
            assertThat(links.getEntityLinks(null, List.of("x")).getFirst().available()).isFalse();
            var results = links.getEntityLinks("Customer", Arrays.asList("invalid", customer.getId().toString(), null, "1-1-1-1-1"));
            assertThat(results).extracting(EntityLinksTool.Link::available).containsExactly(false, true, false, false);
        });
    }

    @Test
    void supportsContextPathWithoutThreadLocalUiAndMissingViews() {
        authenticator.runWithUser("admin", () -> {
            var servlet = new org.springframework.mock.web.MockServletContext();
            servlet.setContextPath("/finance");
            var contextual = new EntityLinksTool(registry, metadata, dataManager, accessManager, views, servlet, messages);
            var ui = com.vaadin.flow.component.UI.getCurrent();
            var session = com.vaadin.flow.server.VaadinSession.getCurrent();
            try {
                com.vaadin.flow.component.UI.setCurrent(null);
                com.vaadin.flow.server.VaadinSession.setCurrent(null);
                assertThat(contextual.getEntityLinks("Customer", List.of(customer.getId().toString())).getFirst().url())
                        .isEqualTo("/finance/customers/" + customer.getId());
                assertThat(contextual.getEntityListLink("Customer").url()).isEqualTo("/finance/customers");
            } finally {
                com.vaadin.flow.component.UI.setCurrent(ui);
                com.vaadin.flow.server.VaadinSession.setCurrent(session);
            }
            var missingViews = org.mockito.Mockito.mock(ViewRegistry.class);
            org.mockito.Mockito.when(missingViews.getDetailViewId(metadata.getClass(Customer.class))).thenReturn("Missing.detail");
            var withoutViews = new EntityLinksTool(registry, metadata, dataManager, accessManager, missingViews, servlet, messages);
            assertThat(withoutViews.getEntityLinks("Customer", List.of(customer.getId().toString())).getFirst().available()).isFalse();
        });
    }

    @ResourceRole(name = "AI test without attributes", code = "ai-test-no-attributes")
    public interface NoAttributes {
        @EntityPolicy(entityClass = Customer.class, actions = EntityPolicyAction.READ)
        @ViewPolicy(viewIds = {"Customer.detail", "Customer.list"})
        void customer();
    }
}
