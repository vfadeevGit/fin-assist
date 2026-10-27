package ru.stnovator.finassist.view.main;

import com.vaadin.flow.server.VaadinSession;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.backgroundtask.BackgroundWorker;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.stnovator.finassist.FinAssistApplication;
import ru.stnovator.finassist.entity.User;
import ru.stnovator.finassist.service.DashboardCountService;
import ru.stnovator.finassist.test_support.DashboardFixtures;
import ru.stnovator.finassist.view.login.LoginView;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@UiTest(initialView = LoginView.class)
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class MainDashboardWorkerUiTest {
    @Autowired BackgroundWorker worker;
    @Autowired DashboardCountService service;
    @Autowired DataManager dataManager;
    @Autowired SystemAuthenticator authenticator;

    @ParameterizedTest
    @ValueSource(strings = {DashboardFixtures.JPQL, DashboardFixtures.PREDICATE})
    void workerUsesInitiatingUserAndDoesNotLeakBetweenUsers(String role) {
        var original = SecurityContextHolder.getContext().getAuthentication();
        try (var fixtures = new DashboardFixtures(dataManager, authenticator)) {
            fixtures.dataset(fixtures.alice, false);
            fixtures.dataset(fixtures.alice, false);
            fixtures.dataset(fixtures.bob, false);
            var loader = new MainDashboardLoader(worker, service);
            assertCount(loader, fixtures.alice, role, 2);
            assertCount(loader, fixtures.bob, role, 1);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(original);
        }
    }

    private void assertCount(MainDashboardLoader loader, User user, String role, long expected) {
        DashboardFixtures.restricted(user, role);
        var caller = SecurityContextHolder.getContext().getAuthentication();
        var session = VaadinSession.getCurrent();
        List<MainDashboardLoader.CountResult> results = new ArrayList<>();
        AtomicBoolean complete = new AtomicBoolean();
        try {
            loader.refresh(UiTestUtils.getCurrentView(), Set.of(DashboardFeature.CUSTOMERS),
                    results::add, () -> complete.set(true));
            await().pollInSameThread().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                session.getService().runPendingAccessTasks(session);
                assertThat(complete).isTrue();
            });
            assertThat(results).singleElement().satisfies(result -> assertThat(result.count()).hasValue(expected));
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(caller);
        } finally {
            loader.cancel();
        }
    }
}
