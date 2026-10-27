package ru.stnovator.finassist.view.main;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.backgroundtask.*;
import io.jmix.flowui.testassist.*;
import io.jmix.flowui.view.View;
import io.jmix.security.model.SecurityScope;
import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.role.annotation.*;
import io.jmix.securityflowui.role.annotation.ViewPolicy;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.stnovator.finassist.FinAssistApplication;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.test_support.DashboardFixtures;
import ru.stnovator.finassist.view.customer.CustomerListView;
import ru.stnovator.finassist.view.contract.ContractListView;
import ru.stnovator.finassist.view.project.ProjectListView;
import ru.stnovator.finassist.view.login.LoginView;
import java.util.*;
import static io.jmix.flowui.testassist.UiTestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@UiTest(initialView = LoginView.class)
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class MainDashboardUiTest {
    @Autowired ViewNavigators navigators;
    @Autowired DataManager dataManager;
    @Autowired SystemAuthenticator authenticator;
    @MockitoBean BackgroundWorker worker;
    final List<BackgroundTask<?, Long>> tasks = new ArrayList<>();
    final List<BackgroundTaskHandler<Long>> handlers = new ArrayList<>();
    DashboardFixtures fixtures;
    Authentication original;

    @BeforeEach @SuppressWarnings("unchecked")
    void setup() {
        original = SecurityContextHolder.getContext().getAuthentication();
        fixtures = new DashboardFixtures(dataManager, authenticator);
        UI.getCurrent().setLocale(Locale.forLanguageTag("ru"));
        VaadinSession.getCurrent().setLocale(Locale.forLanguageTag("ru"));
        when(worker.handle(any())).thenAnswer(invocation -> {
            tasks.add((BackgroundTask<?, Long>) invocation.getArgument(0));
            BackgroundTaskHandler<Long> handler = mock(BackgroundTaskHandler.class);
            handlers.add(handler);
            return handler;
        });
    }
    @AfterEach void cleanup() {
        navigators.view(getCurrentView(), LoginView.class).navigate();
        fixtures.close();
        SecurityContextHolder.getContext().setAuthentication(original);
    }
    MainView home() {
        navigators.view(getCurrentView(), MainView.class).navigate();
        return getCurrentView();
    }
    MainView adminHome() {
        DashboardFixtures.authenticate(fixtures.alice, "ROLE_system-full-access");
        return home();
    }
    Button button(MainView view, String id) { return getComponent(view, id); }
    Span count(MainView view, String prefix) { return getComponent(view, prefix + "Count"); }
    void settle(int offset, long value) { for (int i = offset; i < offset + 3; i++) tasks.get(i).done(value); }

    @Test void rendersSixElementsAndLoadsIndependentResults() {
        MainView view = adminHome();
        assertThat(tasks).hasSize(3);
        for (String prefix : List.of("customers", "contracts", "projects")) {
            Component indicator = getComponent(view, prefix + "Indicator");
            assertThat(indicator.isVisible()).isTrue();
            assertThat(indicator.getElement().getAttribute("tabindex")).isNull();
            assertThat(count(view, prefix).getText()).isEqualTo("Загрузка…");
            assertThat(button(view, prefix + "Tile").isEnabled()).isTrue();
        }
        assertThat(button(view, "dashboardRefreshButton").isEnabled()).isFalse();
        tasks.get(0).done(0L);
        tasks.get(1).handleException(new IllegalStateException("test failure"));
        tasks.get(2).done(1234567L);
        assertThat(count(view, "customers").getText()).isEqualTo("0");
        assertThat(count(view, "contracts").getText()).isEqualTo("Недоступно");
        assertThat(count(view, "projects").getText()).isEqualTo("1\u00a0234\u00a0567");
        assertThat(button(view, "dashboardRefreshButton").isEnabled()).isTrue();
        button(view, "dashboardRefreshButton").click();
        assertThat(tasks).hasSize(6);
        assertThat(count(view, "customers").getText()).isEqualTo("Загрузка…");
        settle(3, 9);
        assertThat(count(view, "contracts").getText()).isEqualTo("9");
        assertThat(getOpenedNotifications()).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"customers,Customer.list", "contracts,Contract.list", "projects,Project.list"})
    void tilesNavigateAndReturnRefreshes(String prefix, String viewId) {
        MainView main = adminHome();
        button(main, prefix + "Tile").click();
        assertThat(getCurrentView().getId()).contains(viewId);
        assertThat(tasks).hasSize(3);
        handlers.forEach(handler -> verify(handler).cancel());
        MainView returned = home();
        assertThat(returned).isSameAs(main);
        assertThat(tasks).hasSize(6);
        tasks.subList(0, 3).forEach(task -> task.done(999L));
        assertThat(count(main, prefix).getText()).isEqualTo("Загрузка…");
        settle(3, 4);
        assertThat(count(main, prefix).getText()).isEqualTo("4");
        assertThat(getOpenedNotifications()).hasSize(1);
    }

    @Test void directChildNavigationDoesNotQueryDashboard() {
        DashboardFixtures.authenticate(fixtures.alice, "ROLE_system-full-access");
        navigators.view(getCurrentView(), CustomerListView.class).navigate();
        assertThat(tasks).isEmpty();
        home();
        assertThat(tasks).hasSize(3);
    }

    @ParameterizedTest
    @CsvSource({"customers,false,false", "customers,true,false", "customers,false,true", "customers,true,true",
            "contracts,false,false", "contracts,true,false", "contracts,false,true", "contracts,true,true",
            "projects,false,false", "projects,true,false", "projects,false,true", "projects,true,true"})
    void requiresBothViewAndReadForEachFeature(String feature, boolean viewAccess, boolean readAccess) {
        List<String> roles = new ArrayList<>(List.of("ROLE_ui-minimal"));
        if (viewAccess) roles.add("ROLE_dashboard-test-view-" + feature);
        if (readAccess) roles.add("ROLE_dashboard-test-read-" + feature);
        DashboardFixtures.authenticate(fixtures.alice, roles.toArray(String[]::new));
        MainView main = home();
        boolean visible = viewAccess && readAccess;
        for (String prefix : List.of("customers", "contracts", "projects")) {
            boolean expected = visible && prefix.equals(feature);
            assertThat(((Component)getComponent(main, prefix + "Indicator")).isVisible()).isEqualTo(expected);
            assertThat(button(main, prefix + "Tile").isVisible()).isEqualTo(expected);
        }
        assertThat(tasks).hasSize(visible ? 1 : 0);
        assertThat(((Component)getComponent(main, "dashboardEmptyMessage")).isVisible()).isEqualTo(!visible);
        assertThat(button(main, "dashboardRefreshButton").isVisible()).isEqualTo(visible);
    }

    @ResourceRole(name="Test customer view", code="dashboard-test-view-customers", scope=SecurityScope.UI)
    public interface CustomerView { @ViewPolicy(viewIds="Customer.list") void policy(); }
    @ResourceRole(name="Test contract view", code="dashboard-test-view-contracts", scope=SecurityScope.UI)
    public interface ContractView { @ViewPolicy(viewIds="Contract.list") void policy(); }
    @ResourceRole(name="Test project view", code="dashboard-test-view-projects", scope=SecurityScope.UI)
    public interface ProjectView { @ViewPolicy(viewIds="Project.list") void policy(); }
    @ResourceRole(name="Test customer read", code="dashboard-test-read-customers", scope=SecurityScope.UI)
    public interface CustomerRead { @EntityPolicy(entityClass=Customer.class, actions=EntityPolicyAction.READ) void policy(); }
    @ResourceRole(name="Test contract read", code="dashboard-test-read-contracts", scope=SecurityScope.UI)
    public interface ContractRead { @EntityPolicy(entityClass=Contract.class, actions=EntityPolicyAction.READ) void policy(); }
    @ResourceRole(name="Test project read", code="dashboard-test-read-projects", scope=SecurityScope.UI)
    public interface ProjectRead { @EntityPolicy(entityClass=Project.class, actions=EntityPolicyAction.READ) void policy(); }
}
