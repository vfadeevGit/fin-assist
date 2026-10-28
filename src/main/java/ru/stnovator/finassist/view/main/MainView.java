package ru.stnovator.finassist.view.main;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.router.Route;
import io.jmix.core.AccessManager;
import io.jmix.core.Metadata;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.app.main.StandardMainView;
import io.jmix.flowui.backgroundtask.BackgroundWorker;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.entity.Customer;
import ru.stnovator.finassist.entity.Project;
import ru.stnovator.finassist.service.DashboardCountService;
import ru.stnovator.finassist.view.contract.ContractListView;
import ru.stnovator.finassist.view.customer.CustomerListView;
import ru.stnovator.finassist.view.project.ProjectListView;

import java.text.NumberFormat;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

@Route("")
@ViewController("MainView")
@ViewDescriptor("main-view.xml")
public class MainView extends StandardMainView {
    @Autowired private CurrentAuthentication currentAuthentication;
    @Autowired private Notifications notifications;
    @Autowired private BackgroundWorker backgroundWorker;
    @Autowired private DashboardCountService countService;
    @Autowired private AccessManager accessManager;
    @Autowired private Metadata metadata;
    @Autowired private ViewNavigators viewNavigators;
    @ViewComponent private MessageBundle messageBundle;
    @ViewComponent private Button dashboardRefreshButton;
    @ViewComponent private Span dashboardEmptyMessage;

    private final EnumMap<DashboardFeature, Cards> cards = new EnumMap<>(DashboardFeature.class);
    private MainDashboardLoader dashboardLoader;
    private boolean welcomeShown;

    private record Cards(Component indicator, Span count, Button tile, Class<?> entity, String viewId) { }

    @Subscribe
    public void onInit(InitEvent event) {
        dashboardLoader = new MainDashboardLoader(backgroundWorker, countService);
        configureFeature(DashboardFeature.CUSTOMERS, "customers", Customer.class, "Customer.list",
                CustomerListView.class, VaadinIcon.USERS);
        configureFeature(DashboardFeature.CONTRACTS, "contracts", Contract.class, "Contract.list",
                ContractListView.class, VaadinIcon.FILE_TEXT);
        configureFeature(DashboardFeature.PROJECTS, "projects", Project.class, "Project.list",
                ProjectListView.class, VaadinIcon.FOLDER_OPEN);
        dashboardRefreshButton.setIcon(VaadinIcon.REFRESH.create());
        dashboardRefreshButton.addClickListener(event1 -> refreshDashboard());
        addDetachListener(event1 -> cancelDashboardLoad());
    }

    private void configureFeature(DashboardFeature feature, String prefix, Class<?> entity,
                                  String viewId, Class<? extends View<?>> target, VaadinIcon iconType) {
        Component indicator = UiComponentUtils.getComponent(this, prefix + "Indicator");
        Span count = (Span) UiComponentUtils.getComponent(this, prefix + "Count");
        Button tile = (Button) UiComponentUtils.getComponent(this, prefix + "Tile");
        var icon = iconType.create();
        icon.getElement().setAttribute("aria-hidden", "true");
        icon.addClassName("dashboard-tile-icon");
        Span title = new Span(messageBundle.getMessage("dashboard." + prefix + ".title"));
        title.addClassName("dashboard-tile-title");
        Span description = new Span(messageBundle.getMessage("dashboard." + prefix + ".description"));
        description.addClassName("dashboard-tile-description");
        Span open = new Span(messageBundle.getMessage("dashboard.open"));
        open.addClassName("dashboard-tile-open");
        Div content = new Div(icon, title, description, open);
        content.addClassName("dashboard-tile-content");
        tile.getElement().appendChild(content.getElement());
        tile.setAriaLabel(title.getText() + ": " + open.getText());
        tile.addClickListener(event -> viewNavigators.view(this, target).navigate());
        indicator.getElement().setAttribute("aria-live", "polite");
        indicator.getElement().setAttribute("aria-atomic", "true");
        cards.put(feature, new Cards(indicator, count, tile, entity, viewId));
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        if (!welcomeShown) {
            notifications.create(messageBundle.formatMessage("welcomeNotification",
                            currentAuthentication.getUser().getUsername()))
                    .withType(Notifications.Type.DEFAULT)
                    .withPosition(Notification.Position.TOP_CENTER)
                    .withDuration(5000)
                    .show();
            welcomeShown = true;
        }
        if (isHome()) {
            refreshDashboard();
        }
    }

    @Override
    public void showRouterLayoutContent(HasElement content) {
        if (content != null) {
            cancelDashboardLoad();
        }
        super.showRouterLayoutContent(content);
    }

    private boolean isHome() {
        return getContent().getContent() == getInitialLayout();
    }

    private Set<DashboardFeature> getAccessibleDashboardFeatures() {
        Set<DashboardFeature> accessible = EnumSet.noneOf(DashboardFeature.class);
        cards.forEach((feature, card) -> {
            var viewAccess = new UiShowViewContext(card.viewId());
            accessManager.applyRegisteredConstraints(viewAccess);
            var entityAccess = new CrudEntityContext(metadata.getClass(card.entity()));
            accessManager.applyRegisteredConstraints(entityAccess);
            if (viewAccess.isPermitted() && entityAccess.isReadPermitted()) {
                accessible.add(feature);
            }
        });
        return accessible;
    }

    private void refreshDashboard() {
        if (!isHome() || dashboardLoader.isLoading()) {
            return;
        }
        Set<DashboardFeature> accessible = getAccessibleDashboardFeatures();
        cards.forEach((feature, card) -> {
            boolean visible = accessible.contains(feature);
            card.indicator().setVisible(visible);
            card.tile().setVisible(visible);
            card.count().setText(visible ? messageBundle.getMessage("dashboard.loading") : "");
            card.count().addClassName("dashboard-count-status");
        });
        dashboardEmptyMessage.setVisible(accessible.isEmpty());
        dashboardRefreshButton.setVisible(!accessible.isEmpty());
        dashboardRefreshButton.setEnabled(false);
        dashboardLoader.refresh(this, accessible, this::renderCount,
                () -> dashboardRefreshButton.setEnabled(true));
    }

    private void renderCount(MainDashboardLoader.CountResult result) {
        if (!isHome()) {
            return;
        }
        Span count = cards.get(result.feature()).count();
        count.setText(result.count().isPresent()
                ? NumberFormat.getIntegerInstance(currentAuthentication.getLocale()).format(result.count().getAsLong())
                : messageBundle.getMessage("dashboard.unavailable"));
        count.setClassName("dashboard-count-status", result.count().isEmpty());
    }

    private void cancelDashboardLoad() {
        if (dashboardLoader != null) {
            dashboardLoader.cancel();
        }
    }
}
