package ru.stnovator.finassist.view.detail;

import com.vaadin.flow.component.datepicker.DatePicker;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.datepicker.TypedDatePicker;
import io.jmix.flowui.component.propertyfilter.PropertyFilter;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.ViewControllerUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.FinAssistApplication;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.view.addendum.AddendumEndDateField;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

@UiTest
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class DateInputConfigurationUiTest {
    @Autowired UiComponents components;
    @Autowired ViewNavigators navigators;

    @Test
    void configuresDocumentAndDynamicFilterDates() {
        navigators.detailView(UiTestUtils.getCurrentView(), Contract.class).newEntity().navigate();
        DatePicker start = UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "startDateField");
        assertConfigured(start);
        navigators.view(UiTestUtils.getCurrentView(), "Contract.list").navigate();
        CollectionLoader<Contract> loader = ViewControllerUtils.getViewData(UiTestUtils.getCurrentView()).getLoader("contractsDl");
        for (int attempt = 0; attempt < 2; attempt++) {
            PropertyFilter<LocalDate> filter = components.create(PropertyFilter.class);
            filter.setDataLoader(loader);
            filter.setProperty("startDate");
            filter.setOperation(PropertyFilter.Operation.EQUAL);
            assertConfigured((DatePicker) filter.getValueComponent());
        }
    }

    @Test
    void preservesCalendarValidationAndReadOnly() {
        TypedDatePicker<LocalDate> picker = components.create(TypedDatePicker.class);
        picker.setRequired(true);
        picker.setMin(LocalDate.of(2020, 1, 1));
        picker.setMax(LocalDate.of(2030, 1, 1));
        picker.setValue(LocalDate.of(2026, 11, 25));
        picker.setReadOnly(true);
        assertConfigured(picker);
        assertThat(picker.isRequired()).isTrue();
        assertThat(picker.isReadOnly()).isTrue();
        assertThat(picker.getMin()).isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(picker.getMax()).isEqualTo(LocalDate.of(2030, 1, 1));
        assertThat(picker.getValue()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(picker.getI18n().getMonthNames()).isNotEmpty();
    }

    @Test
    void configuresCustomAddendumField() {
        assertConfigured(components.create(AddendumEndDateField.class));
    }

    private void assertConfigured(DatePicker picker) {
        assertThat(picker.getElement().getAttribute("data-fin-date-input")).isEqualTo("true");
        assertThat(picker.getI18n().getDateFormats()).contains("dd.MM.yyyy", "ddMMyyyy", "ddMMyy", "dd.MM.yy");
    }
}
