package ru.stnovator.finassist.ai;

import io.jmix.aitoolsflowui.view.chathub.AiChatHubView;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.FinAssistApplication;
import ru.stnovator.finassist.entity.User;
import ru.stnovator.finassist.test_support.DashboardFixtures;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class AiToolsShortcutUiTest {
    @Autowired ViewNavigators navigators;
    @Autowired SystemAuthenticator authenticator;
    @Autowired DataManager dataManager;

    @ParameterizedTest
    @ValueSource(strings = {"Contract.list", "Project.list", "Customer.list", "Addendum.list",
            "PaymentSchedule.list", "ShipmentSchedule.list"})
    void opensChatHubWithoutSelectingARecord(String viewId) {
        authenticator.runWithUser("admin", () -> {
            navigators.view(UiTestUtils.getCurrentView(), viewId).navigate();
            var component = UiComponentUtils.findComponent(UiTestUtils.getCurrentView(), "aiToolsBtn");
            assertThat(component).isPresent();
            var button = (JmixButton) component.orElseThrow();
            assertThat(button.getText()).isEqualTo("Спросить Захара");
            assertThat(button.getTitle()).isEqualTo("Открыть чат с финансовым помощником");
            assertThat(button.isEnabled()).isTrue();
            button.click();
            assertThat((Object) UiTestUtils.getCurrentView()).isInstanceOf(AiChatHubView.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Contract.list", "Project.list", "Customer.list", "Addendum.list",
            "PaymentSchedule.list", "ShipmentSchedule.list"})
    void disablesShortcutWithoutChatAccess(String viewId) {
        authenticator.runWithUser("admin", () -> {
            User user = dataManager.load(User.class).query("e.username = 'admin'").one();
            DashboardFixtures.authenticate(user, "ROLE_finance-manager-role");
            navigators.view(UiTestUtils.getCurrentView(), viewId).navigate();
            var component = UiComponentUtils.findComponent(UiTestUtils.getCurrentView(), "aiToolsBtn");
            assertThat(component).isPresent();
            assertThat(((JmixButton) component.orElseThrow()).isEnabled()).isFalse();
        });
    }
}
