package ru.stnovator.finassist.view.main;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.core.DataManager;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.stnovator.finassist.FinAssistApplication;
import ru.stnovator.finassist.entity.User;
import ru.stnovator.finassist.view.login.LoginView;
import ru.stnovator.finassist.view.user.UserListView;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest(initialView = LoginView.class)
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class MainViewUiTest {

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private DataManager dataManager;

    private Authentication originalAuthentication;

    @BeforeEach
    void setUp() {
        originalAuthentication = SecurityContextHolder.getContext().getAuthentication();
        UI.getCurrent().setLocale(Locale.forLanguageTag("ru"));
        VaadinSession.getCurrent().setLocale(Locale.forLanguageTag("ru"));
    }

    @AfterEach
    void restoreAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(originalAuthentication);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "finance-manager", "<b>finance</b>"})
    void welcomesAuthenticatedUsername(String username) {
        authenticate(username);

        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();

        assertThat(UiTestUtils.getOpenedNotifications()).hasSize(1);
        NotificationInfo welcome = UiTestUtils.getLastOpenedNotification();
        assertThat(welcome).isNotNull();
        assertThat(welcome.getText()).isEqualTo(
                "Добро пожаловать в рабочее место финансового менеджера " + username + "!");
        assertThat(welcome.getNotification().getElement().getTextRecursively()).isEqualTo(
                "Добро пожаловать в рабочее место финансового менеджера " + username + "!");
        assertThat(welcome.getType()).isEqualTo(Notifications.Type.DEFAULT);
        assertThat(welcome.getNotification().getPosition()).isEqualTo(Notification.Position.TOP_CENTER);
        assertThat(welcome.getNotification().getDuration()).isEqualTo(5000);
    }

    @Test
    void welcomesOnChildRouteWithoutRepeatingOnNavigation() {
        authenticate("admin");

        viewNavigators.view(UiTestUtils.getCurrentView(), UserListView.class).navigate();
        assertThat(UiTestUtils.getOpenedNotifications()).hasSize(1);
        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();
        viewNavigators.view(UiTestUtils.getCurrentView(), UserListView.class).navigate();

        assertThat(UiTestUtils.getOpenedNotifications()).hasSize(1);
    }

    @Test
    void welcomesAgainWhenMainViewIsRecreated() {
        authenticate("admin");
        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();
        MainView first = UiTestUtils.getCurrentView();
        viewNavigators.view(UiTestUtils.getCurrentView(), LoginView.class).navigate();

        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();

        assertThat((MainView) UiTestUtils.getCurrentView()).isNotSameAs(first);
        assertThat(UiTestUtils.getOpenedNotifications()).hasSize(2);
    }

    private void authenticate(String username) {
        User user = dataManager.create(User.class);
        user.setUsername(username);
        user.setFirstName("Display name must not replace username");
        user.setAuthorities(originalAuthentication.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }
}
