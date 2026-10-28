package ru.stnovator.finassist.view.related;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.testassist.*;
import io.jmix.flowui.view.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.FinAssistApplication;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.test_support.RelatedFixtures;
import ru.stnovator.finassist.view.customer.CustomerListView;
import ru.stnovator.finassist.view.contract.ContractListView;
import ru.stnovator.finassist.view.project.ProjectListView;
import static org.assertj.core.api.Assertions.*;

@UiTest
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class RelatedNavigationUiTest {
    @Autowired DataManager data;
    @Autowired SystemAuthenticator auth;
    @Autowired ViewNavigators navigators;
    @Autowired io.jmix.flowui.DialogWindows dialogs;
    @Autowired io.jmix.core.AccessManager accessManager;
    @Autowired io.jmix.flowui.backgroundtask.BackgroundWorker worker;
    @Autowired ru.stnovator.finassist.view.support.RelatedRecordsPresentation presentation;
    RelatedFixtures fixtures;
    @BeforeEach void setup() { fixtures = new RelatedFixtures(data,auth); }
    @AfterEach void cleanup() { fixtures.close(); }

    @Test void customersExpandWithoutSelectionOrNavigation() {
        navigators.view(UiTestUtils.getCurrentView(), CustomerListView.class).navigate();
        var view = (CustomerListView) UiTestUtils.getCurrentView();
        DataGrid<Customer> grid = UiTestUtils.getComponent(view,"customersDataGrid");
        verifyToggle(view,grid,fixtures.customer);
    }
    @Test void contractsExpandWithoutSelectionOrNavigation() {
        navigators.view(UiTestUtils.getCurrentView(), ContractListView.class).navigate();
        var view = (ContractListView) UiTestUtils.getCurrentView();
        DataGrid<Contract> grid = UiTestUtils.getComponent(view,"contractsDataGrid");
        verifyToggle(view,grid,fixtures.contract);
    }
    @Test void projectsExpandWithoutSelectionOrNavigation() {
        navigators.view(UiTestUtils.getCurrentView(), ProjectListView.class).navigate();
        var view = (ProjectListView) UiTestUtils.getCurrentView();
        DataGrid<Project> grid = UiTestUtils.getComponent(view,"projectsDataGrid");
        verifyToggle(view,grid,fixtures.project);
    }
    @Test void combinedPanelLoadsTotalsAndPagingWithoutDetailLinks() {
        var date=java.time.LocalDate.of(2026,10,1);
        for(int i=0;i<52;i++) fixtures.shipment(date.plusDays(i),"10");
        fixtures.payment(date,"7");
        navigators.view(UiTestUtils.getCurrentView(),ProjectListView.class).navigate();
        ProjectListView view=UiTestUtils.getCurrentView();
        var panel=presentation.panel(view,ru.stnovator.finassist.view.support.RelatedRecordsPresentation.Kind.SCHEDULE,
                fixtures.project.getId(),0,p -> {}, id -> { throw new AssertionError("Schedule has no detail links"); },null,new java.util.HashMap<>());
        view.getContent().add(panel);
        var grid=(com.vaadin.flow.component.grid.Grid<?>)panel.getChildren().filter(c -> c instanceof com.vaadin.flow.component.grid.Grid).findFirst().orElseThrow();
        awaitRows(grid,50);
        assertThat(grid.getColumnByKey("shipments").getFooterText()).isEqualTo("520,00");
        Button next=findButton(panel,"-next"); next.click(); awaitRows(grid,2);
        assertThat(next.isEnabled()).isFalse();
        assertThat(grid.getColumnByKey("payments").getFooterText()).isEqualTo("7,00");
        panel.cancel();
    }

    @Test void childLinkOpensDetailAndReturnsToExpandedSelectedParent() {
        navigators.view(UiTestUtils.getCurrentView(),CustomerListView.class).navigate();
        CustomerListView view=UiTestUtils.getCurrentView();
        DataGrid<Customer> source=UiTestUtils.getComponent(view,"customersDataGrid");
        source.select(fixtures.customer); toggle(source,fixtures.customer).click();
        var panel=presentation.panel(view,ru.stnovator.finassist.view.support.RelatedRecordsPresentation.Kind.CONTRACTS,
                fixtures.customer.getId(),0,p -> {}, id -> {},null,new java.util.HashMap<>());
        view.getContent().add(panel);
        @SuppressWarnings("unchecked")
        var child=(com.vaadin.flow.component.grid.Grid<Contract>)panel.getChildren().filter(c -> c instanceof com.vaadin.flow.component.grid.Grid).findFirst().orElseThrow();
        awaitRows(child,1);
        @SuppressWarnings("unchecked")
        var renderer=(ComponentRenderer<Component,Contract>)child.getColumnByKey("internalID").getRenderer();
        ((Button)renderer.createComponent(child.getListDataView().getItem(0))).click();
        assertThat((View<?>)UiTestUtils.getCurrentView()).isInstanceOf(ru.stnovator.finassist.view.contract.ContractDetailView.class);
        ru.stnovator.finassist.view.contract.ContractDetailView detail=UiTestUtils.getCurrentView();
        assertThat(detail.getEditedEntity().getId()).isEqualTo(fixtures.contract.getId());
        detail.closeWithDiscard();
        CustomerListView returned=UiTestUtils.getCurrentView();
        DataGrid<Customer> restored=UiTestUtils.getComponent(returned,"customersDataGrid");
        assertThat(restored.isDetailsVisible(fixtures.customer)).isTrue();
        assertThat(restored.getSelectedItems()).extracting("id").containsExactly(fixtures.customer.getId());
    }

    @Test void reorderedQueryParametersPreserveExpandedRowsOnReturn() {
        var first=new java.util.LinkedHashMap<String,String>();
        first.put("pagination_firstResult","0"); first.put("pagination_maxResults","50");
        navigators.view(UiTestUtils.getCurrentView(),CustomerListView.class)
                .withQueryParameters(com.vaadin.flow.router.QueryParameters.simple(first)).navigate();
        CustomerListView view=UiTestUtils.getCurrentView();
        DataGrid<Customer> grid=UiTestUtils.getComponent(view,"customersDataGrid");
        toggle(grid,fixtures.customer).click();
        navigators.detailView(view,Contract.class).editEntity(fixtures.contract).withBackwardNavigation(true).navigate();
        var reversed=new java.util.LinkedHashMap<String,String>();
        reversed.put("pagination_maxResults","50"); reversed.put("pagination_firstResult","0");
        navigators.view(UiTestUtils.getCurrentView(),CustomerListView.class)
                .withQueryParameters(com.vaadin.flow.router.QueryParameters.simple(reversed)).navigate();
        CustomerListView returned=UiTestUtils.getCurrentView();
        DataGrid<Customer> restored=UiTestUtils.getComponent(returned,"customersDataGrid");
        assertThat(restored.isDetailsVisible(fixtures.customer)).isTrue();
    }

    @Test void collapsedStateRemainsReachableAfterReadPermissionIsRevoked() {
        navigators.view(UiTestUtils.getCurrentView(),CustomerListView.class).navigate();
        CustomerListView view=UiTestUtils.getCurrentView();
        DataGrid<Customer> grid=UiTestUtils.getComponent(view,"customersDataGrid");
        Button button=toggle(grid,fixtures.customer); button.click();
        var original=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        User user=data.create(User.class); user.setUsername("related-no-access");
        try {
            ru.stnovator.finassist.test_support.DashboardFixtures.authenticate(user);
            button.click();
            assertThat(grid.isDetailsVisible(fixtures.customer)).isFalse();
            button.click();
            assertThat(grid.isDetailsVisible(fixtures.customer)).isFalse();
        } finally { org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(original); }
    }

    @Test void financeManagerCanOpenBothRelatedDetails() {
        var original=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        User user=data.create(User.class); user.setUsername("related-finance-reader");
        try {
            ru.stnovator.finassist.test_support.DashboardFixtures.authenticate(user,"ROLE_finance-manager-role");
            for(String id:java.util.List.of("Contract.detail","Project.detail")) {
                var context=new io.jmix.flowui.accesscontext.UiShowViewContext(id);
                accessManager.applyRegisteredConstraints(context);
                assertThat(context.isPermitted()).as(id).isTrue();
            }
        } finally { org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(original); }
    }

    @Test void lookupModeHasNoExpansionColumn() {
        var window=dialogs.lookup(UiTestUtils.getCurrentView(),Customer.class).withViewClass(CustomerListView.class)
                .withSelectHandler(items -> {}).build();
        window.open();
        DataGrid<Customer> grid=UiTestUtils.getComponent(window.getView(),"customersDataGrid");
        assertThat(grid.getColumnByKey("relatedDetails").isVisible()).isFalse();
        window.getView().closeWithDefaultAction();
    }

    @Test void parentReloadCollapsesExpandedRows() {
        navigators.view(UiTestUtils.getCurrentView(),CustomerListView.class).navigate();
        CustomerListView view=UiTestUtils.getCurrentView();
        DataGrid<Customer> grid=UiTestUtils.getComponent(view,"customersDataGrid");
        Customer item=grid.getItems().getItems().iterator().next();
        toggle(grid,item).click(); assertThat(grid.isDetailsVisible(item)).isTrue();
        ViewControllerUtils.getViewData(view).getLoader("customersDl").load();
        assertThat(grid.isDetailsVisible(item)).isFalse();
    }

    @Test void reattachingPanelRestartsCancelledLoad() {
        navigators.view(UiTestUtils.getCurrentView(),ProjectListView.class).navigate();
        ProjectListView view=UiTestUtils.getCurrentView();
        var grid=new com.vaadin.flow.component.grid.Grid<String>();
        grid.addColumn(value -> value);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var panel=new ru.stnovator.finassist.view.support.RelatedPagePanel<String>(view,"reattach-test","test",grid,0,worker,p -> {
            if(calls.incrementAndGet()==1) {
                entered.countDown();
                try { release.await(10,java.util.concurrent.TimeUnit.SECONDS); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return new ru.stnovator.finassist.view.support.RelatedPagePanel.Result<>(java.util.List.of("ready"),false,() -> {});
        },p -> {},key -> key);
        try {
            view.getContent().add(panel);
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() -> entered.getCount()==0);
            panel.removeFromParent();
            view.getContent().add(panel);
            release.countDown();
            awaitRows(grid,1);
            assertThat(calls.get()).isEqualTo(2);
        } finally { release.countDown(); panel.cancel(); }
    }

    private void awaitRows(com.vaadin.flow.component.grid.Grid<?> grid,int count) {
        var session=com.vaadin.flow.server.VaadinSession.getCurrent();
        org.awaitility.Awaitility.await().pollInSameThread().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> {
            session.getService().runPendingAccessTasks(session);
            assertThat(grid.isVisible()).isTrue();
            assertThat(grid.getListDataView().getItemCount()).isEqualTo(count);
        });
    }
    private Button findButton(Component parent,String id) {
        return children(parent).filter(c -> c instanceof Button && c.getId().orElse("").endsWith(id)).map(c -> (Button)c).findFirst().orElseThrow();
    }
    private java.util.stream.Stream<Component> children(Component parent) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(parent),parent.getChildren().flatMap(this::children));
    }
    @SuppressWarnings("unchecked")
    private <T> Button toggle(DataGrid<T> grid,T item) {
        return (Button)((ComponentRenderer<Component,T>)grid.getColumnByKey("relatedDetails").getRenderer()).createComponent(item);
    }
    @SuppressWarnings("unchecked")
    private <T> void verifyToggle(View<?> view, DataGrid<T> grid, T item) {
        assertThat(grid.getColumnByKey("relatedDetails")).isNotNull();
        var renderer = (ComponentRenderer<Component,T>) grid.getColumnByKey("relatedDetails").getRenderer();
        Button button = (Button) renderer.createComponent(item);
        assertThat(button.getElement().getAttribute("aria-expanded")).isEqualTo("false");
        button.click();
        assertThat(grid.isDetailsVisible(item)).isTrue();
        assertThat(button.getElement().getAttribute("aria-expanded")).isEqualTo("true");
        assertThat(grid.getSelectedItems()).isEmpty();
        assertThat((View<?>) UiTestUtils.getCurrentView()).isSameAs(view);
        button.click();
        assertThat(grid.isDetailsVisible(item)).isFalse();
    }
}
