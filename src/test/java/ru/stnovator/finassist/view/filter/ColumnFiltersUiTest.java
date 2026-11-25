package ru.stnovator.finassist.view.filter;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.button.Button;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.genericfilter.GenericFilter;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.grid.DataGridColumn;
import io.jmix.flowui.component.grid.headerfilter.DataGridHeaderFilter;
import io.jmix.flowui.component.propertyfilter.PropertyFilter;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.testassist.*;
import io.jmix.flowui.view.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.FinAssistApplication;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.test_support.RelatedFixtures;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

@UiTest
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class ColumnFiltersUiTest {
    @Autowired DataManager data;
    @Autowired SystemAuthenticator auth;
    @Autowired ViewNavigators navigators;
    @Autowired UiComponents components;

    @ParameterizedTest
    @CsvSource({"Customer.list,customers", "Contract.list,contracts", "Project.list,projects", "Addendum.list,addenda"})
    void allDataColumnsUseNativeFilters(String id, String prefix) {
        navigators.view(UiTestUtils.getCurrentView(), id).navigate();
        DataGrid<?> grid = UiTestUtils.getComponent(UiTestUtils.getCurrentView(), prefix + "DataGrid");
        Button reset = UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "clearColumnFiltersBtn");
        assertThat(reset.getText()).isEqualTo("Сбросить все");
        for (var column : grid.getColumns()) {
            assertThat(((DataGridColumn<?>) column).isFilterable())
                    .as(id + "/" + column.getKey()).isEqualTo(!"relatedDetails".equals(column.getKey()));
        }
    }

    @Test
    void columnFiltersIntersectGenericFilterAndResetOnlyColumns() {
        try (var fixtures = new RelatedFixtures(data, auth)) {
            navigators.view(UiTestUtils.getCurrentView(), "Contract.list").navigate();
            View<?> view = UiTestUtils.getCurrentView();
            CollectionLoader<Contract> loader = ViewControllerUtils.getViewData(view).getLoader("contractsDl");
            DataGrid<Contract> grid = UiTestUtils.getComponent(view, "contractsDataGrid");
            GenericFilter generic = UiTestUtils.getComponent(view, "genericFilter");
            PropertyFilter<String> condition = components.create(PropertyFilter.class);
            condition.setDataLoader(loader);
            condition.setProperty("internalID");
            condition.setOperation(PropertyFilter.Operation.EQUAL);
            condition.setParameterName("testGenericId");
            generic.getCurrentConfiguration().getRootLogicalFilterComponent().add(condition);
            condition.setValue(fixtures.contract.getInternalID());
            generic.apply();
            assertThat(loader.getContainer().getItems()).extracting(Contract::getId).containsExactly(fixtures.contract.getId());

            apply(grid, "customer", PropertyFilter.Operation.EQUAL, fixtures.customer);
            apply(grid, "startDate", PropertyFilter.Operation.GREATER, LocalDate.of(2030, 1, 1));
            assertThat(loader.getContainer().getItems()).isEmpty();
            Button reset = UiTestUtils.getComponent(view, "clearColumnFiltersBtn");
            reset.click();
            assertThat(condition.getValue()).isEqualTo(fixtures.contract.getInternalID());
            assertThat(loader.getContainer().getItems()).extracting(Contract::getId).containsExactly(fixtures.contract.getId());
            assertThat(header(grid, "customer").getPropertyFilter().getValue()).isNull();
            assertThat(header(grid, "startDate").isFilterApplied()).isFalse();
        }
    }

    @Test
    void nestedCustomerFilterSearchesFullDatasetAndResetsPage() {
        try (var fixtures = new RelatedFixtures(data, auth);
             var other = new RelatedFixtures(data, auth)) {
            Addendum target = data.create(Addendum.class);
            target.setContract(fixtures.contract); target.setNumber("target");
            target.setEffectiveDate(LocalDate.of(2020, 1, 1)); target = fixtures.save(target);
            Addendum latest = data.create(Addendum.class);
            latest.setContract(other.contract); latest.setNumber("other");
            latest.setEffectiveDate(LocalDate.of(2026, 1, 1)); other.save(latest);
            navigators.view(UiTestUtils.getCurrentView(), "Addendum.list").navigate();
            View<?> view = UiTestUtils.getCurrentView();
            CollectionLoader<Addendum> loader = ViewControllerUtils.getViewData(view).getLoader("addendaDl");
            DataGrid<Addendum> grid = UiTestUtils.getComponent(view, "addendaDataGrid");
            loader.setMaxResults(1); loader.setFirstResult(0); loader.load();
            assertThat(loader.getContainer().getItems()).extracting(Addendum::getId).doesNotContain(target.getId());
            loader.setFirstResult(1); loader.load();
            apply(grid, "contract.customer", PropertyFilter.Operation.EQUAL, fixtures.customer);
            assertThat(loader.getFirstResult()).isZero();
            assertThat(loader.getContainer().getItems()).extracting(Addendum::getId).containsExactly(target.getId());
        }
    }

    static DataGridHeaderFilter header(DataGrid<?> grid, String key) {
        return (DataGridHeaderFilter) grid.getColumnByKey(key).getHeaderComponent();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void apply(DataGrid<?> grid, String key, PropertyFilter.Operation operation, Object value) {
        var header = header(grid, key);
        PropertyFilter filter = header.getPropertyFilter();
        filter.setOperation(operation);
        filter.setValue(value);
        header.apply();
        ComponentUtil.fireEvent(header, new DataGridHeaderFilter.ApplyEvent(header, filter, false));
    }
}
