package ru.stnovator.finassist.view.filter;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.genericfilter.GenericFilter;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.propertyfilter.PropertyFilter;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.testassist.*;
import io.jmix.flowui.view.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.FinAssistApplication;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.test_support.RelatedFixtures;
import ru.stnovator.finassist.view.customer.CustomerListView;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
import static ru.stnovator.finassist.view.filter.ColumnFiltersUiTest.*;

@UiTest
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class ColumnFilterNavigationUiTest {
    @Autowired DataManager data;
    @Autowired SystemAuthenticator auth;
    @Autowired ViewNavigators navigators;
    @Autowired UiComponents components;
    @Autowired DialogWindows dialogs;

    @ParameterizedTest
    @CsvSource({"Customer.list,customers,name", "Contract.list,contracts,internalID", "Project.list,projects,name", "Addendum.list,addenda,number"})
    @SuppressWarnings({"rawtypes", "unchecked"})
    void restoresBothLayersAndDoesNotResurrectClearedFilters(String id, String prefix, String property) {
        try (var fixtures = new RelatedFixtures(data, auth)) {
            Addendum addendum = data.create(Addendum.class);
            addendum.setContract(fixtures.contract); addendum.setNumber(fixtures.customer.getName());
            addendum.setEffectiveDate(LocalDate.of(2026, 1, 1)); addendum = fixtures.save(addendum);
            Object entity = switch (prefix) {
                case "customers" -> fixtures.customer;
                case "contracts" -> fixtures.contract;
                case "projects" -> fixtures.project;
                default -> addendum;
            };
            navigators.view(UiTestUtils.getCurrentView(), id).navigate();
            View<?> view = UiTestUtils.getCurrentView();
            var parameters = captureBrowserUrlUpdates(view);
            DataGrid<?> grid = UiTestUtils.getComponent(view, prefix + "DataGrid");
            CollectionLoader<?> loader = ViewControllerUtils.getViewData(view).getLoader(prefix + "Dl");
            GenericFilter generic = UiTestUtils.getComponent(view, "genericFilter");
            PropertyFilter<String> condition = components.create(PropertyFilter.class);
            condition.setDataLoader(loader); condition.setProperty(property);
            condition.setOperation(PropertyFilter.Operation.EQUAL); condition.setParameterName("navGeneric");
            generic.getCurrentConfiguration().getRootLogicalFilterComponent().add(condition);
            condition.setValue(fixtures.customer.getName()); generic.apply();
            apply(grid, property, PropertyFilter.Operation.CONTAINS, fixtures.customer.getName());
            if (!prefix.equals("customers")) {
                String reference = prefix.equals("contracts") ? "customer" : "contract";
                apply(grid, reference, PropertyFilter.Operation.EQUAL,
                        prefix.equals("contracts") ? fixtures.customer : fixtures.contract);
            }
            var query = parameters;
            assertThat(query).containsKey(prefix + "DataGridFilter");

            // Navigate with the real binder output: headless TestUI cannot answer fetchCurrentURL.
            parameters.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            navigators.view(view, id).withQueryParameters(new com.vaadin.flow.router.QueryParameters(parameters)).navigate();
            view = UiTestUtils.getCurrentView();
            navigators.detailView(view, (Class) entity.getClass()).editEntity(entity).withBackwardNavigation(true).navigate();
            ((StandardDetailView<?>) UiTestUtils.getCurrentView()).closeWithDiscard();
            View<?> returned = UiTestUtils.getCurrentView();
            var resetParameters = captureBrowserUrlUpdates(returned);
            DataGrid<?> restored = UiTestUtils.getComponent(returned, prefix + "DataGrid");
            assertThat(header(restored, property).getPropertyFilter().getValue()).isEqualTo(fixtures.customer.getName());
            assertThat(header(restored, property).getPropertyFilter().getOperation()).isEqualTo(PropertyFilter.Operation.CONTAINS);
            CollectionLoader<?> restoredLoader = ViewControllerUtils.getViewData(returned).getLoader(prefix + "Dl");
            assertThat(restoredLoader.getContainer().getItems()).hasSize(1);
            GenericFilter restoredGeneric = UiTestUtils.getComponent(returned, "genericFilter");
            assertThat(restoredGeneric.getCurrentConfiguration().getRootLogicalFilterComponent().getOwnFilterComponents()).hasSize(1);

            ((Button) UiTestUtils.getComponent(returned, "clearColumnFiltersBtn")).click();
            assertThat(resetParameters.get(prefix + "DataGridFilter")).isEmpty();
            resetParameters.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            navigators.view(returned, id).withQueryParameters(new com.vaadin.flow.router.QueryParameters(resetParameters)).navigate();
            returned = UiTestUtils.getCurrentView();
            navigators.detailView(returned, (Class) entity.getClass()).editEntity(entity).withBackwardNavigation(true).navigate();
            ((StandardDetailView<?>) UiTestUtils.getCurrentView()).closeWithDiscard();
            View<?> cleared = UiTestUtils.getCurrentView();
            DataGrid<?> clearedGrid = UiTestUtils.getComponent(cleared, prefix + "DataGrid");
            assertThat(header(clearedGrid, property).getPropertyFilter().getValue()).isNull();
            GenericFilter keptGeneric = UiTestUtils.getComponent(cleared, "genericFilter");
            assertThat(keptGeneric.getCurrentConfiguration().getRootLogicalFilterComponent().getOwnFilterComponents()).hasSize(1);
            CollectionLoader<?> keptLoader = ViewControllerUtils.getViewData(cleared).getLoader(prefix + "Dl");
            assertThat(keptLoader.getContainer().getItems()).hasSize(1);
        }
    }

    private java.util.Map<String, java.util.List<String>> captureBrowserUrlUpdates(View<?> view) {
        var parameters = new java.util.HashMap<>(
                UI.getCurrent().getInternals().getActiveViewLocation().getQueryParameters().getParameters());
        var facet = ViewControllerUtils.getViewFacet(view, io.jmix.flowui.facet.UrlQueryParametersFacet.class);
        facet.getBinders().forEach(binder -> binder.addUrlQueryParametersChangeListener(event ->
                parameters.putAll(event.getQueryParameters().getParameters())));
        return parameters;
    }

    @Test
    void lookupDoesNotInheritStandaloneColumnState() {
        navigators.view(UiTestUtils.getCurrentView(), "Customer.list").navigate();
        DataGrid<?> standalone = UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "customersDataGrid");
        apply(standalone, "name", PropertyFilter.Operation.CONTAINS, "standalone-only");
        var window = dialogs.lookup(UiTestUtils.getCurrentView(), Customer.class)
                .withViewClass(CustomerListView.class).withSelectHandler(items -> {}).build();
        window.open();
        DataGrid<?> lookup = UiTestUtils.getComponent(window.getView(), "customersDataGrid");
        assertThat(header(lookup, "name").getPropertyFilter().getValue()).isNull();
        window.getView().closeWithDefaultAction();
        assertThat(header(standalone, "name").getPropertyFilter().getValue()).isEqualTo("standalone-only");
    }
}
