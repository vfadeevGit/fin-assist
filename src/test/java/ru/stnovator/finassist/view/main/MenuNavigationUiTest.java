package ru.stnovator.finassist.view.main;

import com.vaadin.flow.component.UI;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.main.JmixListMenu;
import io.jmix.flowui.menu.MenuConfig;
import io.jmix.flowui.menu.MenuItem;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.FinAssistApplication;
import ru.stnovator.finassist.entity.User;
import ru.stnovator.finassist.test_support.DashboardFixtures;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class MenuNavigationUiTest {
    @Autowired MenuConfig menuConfig;
    @Autowired UiComponents uiComponents;
    @Autowired SystemAuthenticator authenticator;
    @Autowired DataManager dataManager;
    @Autowired ViewNavigators navigators;

    @Test
    void aiEntryIsFirstAndDoesNotDuplicateAddonEntries() {
        authenticator.runWithUser("admin", () -> {
            var roots = menuConfig.getRootItems();
            assertThat(roots.getFirst().getView()).isEqualTo("aitls_AiChatHubView");
            assertThat(roots.getFirst().getIcon()).isNotNull();
            assertThat(flatten(roots).filter(i -> i.getView() != null).map(MenuItem::getView).toList())
                    .doesNotHaveDuplicates();
            var graphs = roots.stream().filter(i -> i.getId().equals("graphs")).findFirst().orElseThrow();
            assertThat(graphs.getChildren()).extracting(MenuItem::getView).containsExactly(
                    "ShipmentSchedule.list", "ShipmentScheduleCorrection.list",
                    "PaymentSchedule.list", "PaymentScheduleCorrection.list");
        });
    }

    @Test
    void usersAndAiAreHiddenWithoutTheirExistingPermissions() {
        authenticator.runWithUser("admin", () -> {
            User user = dataManager.load(User.class).query("e.username = 'admin'").one();
            DashboardFixtures.authenticate(user, "ROLE_finance-manager-role", "ROLE_ui-minimal");
            JmixListMenu menu = uiComponents.create(JmixListMenu.class);
            menu.loadMenuConfig();
            assertThat(menu.getMenuItem("User.list")).isNull();
            assertThat(menu.getMenuItem("aitls_AiChatHubView")).isNull();
            assertThat(menu.getMenuItem("Contract.list")).isNotNull();
            assertThat(menu.getMenuItem("report_ReportRunView")).isNotNull();
        });
    }

    @Test
    void menuTargetsOpenExistingAiAndReportScreens() {
        authenticator.runWithUser("admin", () -> {
            UI.getCurrent().setLocale(Locale.forLanguageTag("ru"));
            JmixListMenu menu = uiComponents.create(JmixListMenu.class);
            menu.loadMenuConfig();
            for (String id : List.of("aitls_AiChatHubView", "report_ReportRunView")) {
                var item = (JmixListMenu.ViewMenuItem) menu.getMenuItem(id);
                assertThat(item).isNotNull();
                assertThat(item.getControllerClass()).isNotNull();
                navigators.view(UiTestUtils.getCurrentView(), item.getControllerClass()).navigate();
                assertThat(UiTestUtils.getCurrentView().getClass()).isEqualTo(item.getControllerClass());
            }
            assertThat(menu.getMenuItem("report_ReportRunView").getTitle()).isEqualTo("Аналитика и отчеты");
        });
    }

    @Test
    void allBusinessMenuLabelsAreLocalized() {
        authenticator.runWithUser("admin", () -> {
            UI.getCurrent().setLocale(Locale.forLanguageTag("ru"));
            JmixListMenu menu = uiComponents.create(JmixListMenu.class);
            menu.loadMenuConfig();
            var expected = java.util.Map.of(
                    "Contract.list", "Контракты", "Project.list", "Проекты",
                    "Addendum.list", "Дополнительные соглашения",
                    "ShipmentSchedule.list", "План отгрузок", "PaymentSchedule.list", "План оплат",
                    "Customer.list", "Клиенты", "LineOfBusiness.list", "Подразделения", "User.list", "Пользователи");
            expected.forEach((id, title) -> assertThat(menu.getMenuItem(id).getTitle()).as(id).isEqualTo(title));
        });
    }

    @Test
    void homeRouteIsUnchanged() {
        authenticator.runWithUser("admin", () -> {
            navigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();
            assertThat((Object) UiTestUtils.getCurrentView()).isInstanceOf(MainView.class);
            assertThat((Object) UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "dashboard")).isNotNull();
        });
    }

    private Stream<MenuItem> flatten(List<MenuItem> items) {
        return items.stream().flatMap(item -> Stream.concat(Stream.of(item), flatten(item.getChildren())));
    }
}
