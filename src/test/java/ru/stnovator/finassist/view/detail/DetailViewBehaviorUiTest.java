package ru.stnovator.finassist.view.detail;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.core.DataManager;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.tabsheet.JmixTabSheet;
import io.jmix.flowui.component.textfield.JmixBigDecimalField;
import io.jmix.flowui.exception.ValidationException;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.ViewControllerUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.stnovator.finassist.FinAssistApplication;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.view.contract.ContractDetailView;
import ru.stnovator.finassist.view.project.ProjectDetailView;

import java.math.BigDecimal;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;

@UiTest
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class DetailViewBehaviorUiTest {
    @Autowired private ViewNavigators viewNavigators;
    @Autowired private DataManager dataManager;

    @BeforeEach
    void locale() {
        UI.getCurrent().setLocale(Locale.forLanguageTag("ru"));
        VaadinSession.getCurrent().setLocale(Locale.forLanguageTag("ru"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Shipment", "Payment"})
    void scheduleRowActionsFollowSelectionAndSchedule(String kind) {
        ProjectDetailView view = newProject();
        JmixTabSheet tabs = UiTestUtils.getComponent(view, "projectTabs");
        tabs.setSelectedIndex(kind.equals("Shipment") ? 0 : 1);
        String prefix = Character.toLowerCase(kind.charAt(0)) + kind.substring(1);
        Button create = UiTestUtils.getComponent(view, "create" + kind + "ScheduleBtn");
        Button remove = UiTestUtils.getComponent(view, "remove" + kind + "ScheduleBtn");
        Button editItem = UiTestUtils.getComponent(view, "edit" + kind + "ScheduleItemBtn");
        Button removeItem = UiTestUtils.getComponent(view, "remove" + kind + "ScheduleItemBtn");
        assertThat(create.isVisible()).isTrue();
        assertThat(remove.isVisible()).isFalse();
        DataGrid<?> emptyGrid = UiTestUtils.getComponent(view, prefix + "ScheduleItemsDataGrid");
        String absentHint = emptyGrid.getEmptyStateText();
        create.click();
        assertThat(emptyGrid.getEmptyStateText()).isNotEqualTo(absentHint);
        assertThat(create.isVisible()).isFalse();
        assertThat(remove.isVisible()).isTrue();
        assertThat(editItem.isEnabled()).isFalse();
        assertThat(removeItem.isEnabled()).isFalse();
        DataGrid<Object> grid = UiTestUtils.getComponent(view, prefix + "ScheduleItemsDataGrid");
        CollectionContainer<Object> items = ViewControllerUtils.getViewData(view).getContainer(prefix + "ScheduleItemsDc");
        Object item;
        if (kind.equals("Shipment")) {
            ShipmentScheduleItem shipment = dataManager.create(ShipmentScheduleItem.class);
            shipment.setSchedule(view.getEditedEntity().getShipmentSchedule());
            item = shipment;
        } else {
            PaymentScheduleItem payment = dataManager.create(PaymentScheduleItem.class);
            payment.setSchedule(view.getEditedEntity().getPaymentSchedule());
            item = payment;
        }
        items.getMutableItems().add(item);
        grid.select(item);
        assertThat(editItem.isEnabled()).isTrue();
        assertThat(removeItem.isEnabled()).isTrue();
        var originalAuthentication = SecurityContextHolder.getContext().getAuthentication();
        User restrictedUser = dataManager.create(User.class);
        restrictedUser.setUsername("detail-view-no-permissions");
        try {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(restrictedUser, null, java.util.List.of()));
            view.onBeforeShow(null);
            assertThat(editItem.isEnabled()).isFalse();
            assertThat(removeItem.isEnabled()).isFalse();
            grid.getAction("remove").actionPerform(grid);
            assertThat(items.getItems()).containsExactly(item);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(originalAuthentication);
        }
        grid.deselectAll();
        assertThat(editItem.isEnabled()).isFalse();
        assertThat(removeItem.isEnabled()).isFalse();
        assertThatCode(() -> grid.getAction("edit").actionPerform(grid)).doesNotThrowAnyException();
        assertThatCode(() -> grid.getAction("remove").actionPerform(grid)).doesNotThrowAnyException();
        assertThat(items.getItems()).containsExactly(item);
        remove.click();
        assertThat(emptyGrid.getEmptyStateText()).isEqualTo(absentHint);
        assertThat(create.isVisible()).isTrue();
        assertThat(remove.isVisible()).isFalse();
        assertThat(editItem.isEnabled()).isFalse();
    }

    @Test
    void staleEditInvocationWithNoSelectionIsHarmless() {
        ProjectDetailView view = newProject();
        Button create = UiTestUtils.getComponent(view, "createShipmentScheduleBtn");
        create.click();
        DataGrid<?> grid = UiTestUtils.getComponent(view, "shipmentScheduleItemsDataGrid");
        assertThatCode(() -> grid.getAction("edit").actionPerform(grid)).doesNotThrowAnyException();
    }

    @Test
    void paymentDaysKeepWholeNumberAndNullBindings() {
        viewNavigators.detailView(UiTestUtils.getCurrentView(), Contract.class).newEntity().navigate();
        ContractDetailView view = UiTestUtils.getCurrentView();
        JmixBigDecimalField field = UiTestUtils.getComponent(view, "paymentDaysField");
        view.getEditedEntity().setPaymentDays(new BigDecimal("15"));
        assertThat(field.getElement().getProperty("value")).isEqualTo("15");
        field.setValue(new BigDecimal("27"));
        assertThat(view.getEditedEntity().getPaymentDays()).isEqualByComparingTo("27");
        field.clear();
        assertThat(view.getEditedEntity().getPaymentDays()).isNull();
    }

    @Test
    void paymentDaysRejectFractionsWithoutRounding() {
        viewNavigators.detailView(UiTestUtils.getCurrentView(), Contract.class).newEntity().navigate();
        ContractDetailView view = UiTestUtils.getCurrentView();
        JmixBigDecimalField field = UiTestUtils.getComponent(view, "paymentDaysField");
        field.setValue(new BigDecimal("15.5"));
        assertThatThrownBy(field::executeValidators).isInstanceOf(ValidationException.class)
                .hasMessageContaining("цел");
        assertThat(view.getEditedEntity().getPaymentDays()).isEqualByComparingTo("15.5");
        field.setValue(new BigDecimal("15"));
        assertThatCode(field::executeValidators).doesNotThrowAnyException();
    }

    private ProjectDetailView newProject() {
        viewNavigators.detailView(UiTestUtils.getCurrentView(), Project.class).newEntity().navigate();
        return UiTestUtils.getCurrentView();
    }
}
