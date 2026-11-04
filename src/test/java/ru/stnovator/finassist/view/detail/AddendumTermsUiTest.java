package ru.stnovator.finassist.view.detail;

import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.datepicker.TypedDatePicker;
import io.jmix.flowui.component.valuepicker.EntityPicker;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.FinAssistApplication;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.test_support.RelatedFixtures;
import ru.stnovator.finassist.test_support.DashboardFixtures;
import ru.stnovator.finassist.view.addendum.AddendumDetailView;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

@UiTest
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class AddendumTermsUiTest {
    @Autowired DataManager data;
    @Autowired SystemAuthenticator auth;
    @Autowired ViewNavigators navigators;
    @Autowired io.jmix.flowui.DialogWindows dialogs;
    RelatedFixtures fixture;

    @BeforeEach void setup() {
        fixture = new RelatedFixtures(data, auth);
        fixture.contract.setTotalAmount(new BigDecimal("1000"));
        fixture.contract = auth.withUser("admin", () -> data.save(fixture.contract));
    }
    @AfterEach void cleanup() { fixture.close(); }

    @Test void previewReactsToDateDeltaAndContractAndCancelDoesNotSave() {
        Addendum existing = data.create(Addendum.class);
        existing.setNumber("first"); existing.setContract(fixture.contract);
        existing.setEffectiveDate(LocalDate.of(2026, 2, 1)); existing.setAmountAdjustment(new BigDecimal("200"));
        fixture.save(existing);
        navigators.detailView(UiTestUtils.getCurrentView(), Addendum.class)
                .withViewClass(AddendumDetailView.class).newEntity().navigate();
        AddendumDetailView view = UiTestUtils.getCurrentView();
        EntityPicker<Contract> contractField = UiTestUtils.getComponent(view, "contractField");
        TypedDatePicker<LocalDate> date = UiTestUtils.getComponent(view, "effectiveDateField");
        TypedTextField<BigDecimal> delta = UiTestUtils.getComponent(view, "amountAdjustmentField");
        TypedTextField<BigDecimal> before = UiTestUtils.getComponent(view, "amountBeforeField");
        TypedTextField<BigDecimal> after = UiTestUtils.getComponent(view, "amountAfterField");
        TypedDatePicker<LocalDate> end = UiTestUtils.getComponent(view, "newEndDateField");
        TypedDatePicker<LocalDate> endAfter = UiTestUtils.getComponent(view, "endDateAfterField");
        contractField.setValue(fixture.contract); date.setValue(LocalDate.of(2026, 3, 1));
        delta.setTypedValue(new BigDecimal("-200"));
        assertThat(before.getTypedValue()).isEqualByComparingTo("1200");
        assertThat(after.getTypedValue()).isEqualByComparingTo("1000");
        assertThat(after.isReadOnly()).isTrue();
        date.setValue(LocalDate.of(2026, 1, 15));
        assertThat(after.getTypedValue()).isEqualByComparingTo("800");
        end.setValue(LocalDate.of(2028, 1, 1));
        assertThat(endAfter.getValue()).isEqualTo(LocalDate.of(2028, 1, 1));
        try (RelatedFixtures other = new RelatedFixtures(data, auth)) {
            other.contract.setTotalAmount(new BigDecimal("3000")); other.contract = data.save(other.contract);
            contractField.setValue(other.contract);
            assertThat(after.getTypedValue()).isEqualByComparingTo("2800");
            var draftId = view.getEditedEntity().getId();
            view.closeWithDiscard();
            assertThat(data.load(Addendum.class).id(draftId).optional()).isEmpty();
        }
    }

    @Test void financialManagerCanEditAndSaveZeroWithNoDoubleCount() {
        var a = data.create(Addendum.class); a.setContract(fixture.contract); a.setNumber("zero");
        a.setEffectiveDate(LocalDate.of(2026, 5, 1)); a.setAmountAdjustment(new BigDecimal("-200"));
        a = fixture.save(a);
        User manager = data.create(User.class); manager.setUsername("terms-manager");
        DashboardFixtures.authenticate(manager, "ROLE_finance-manager-role", "ROLE_ui-minimal");
        navigators.detailView(UiTestUtils.getCurrentView(), Addendum.class)
                .withViewClass(AddendumDetailView.class).editEntity(a).navigate();
        AddendumDetailView view = UiTestUtils.getCurrentView();
        TypedTextField<BigDecimal> after = UiTestUtils.getComponent(view, "amountAfterField");
        TypedTextField<BigDecimal> delta = UiTestUtils.getComponent(view, "amountAdjustmentField");
        assertThat(after.getTypedValue()).isEqualByComparingTo("800");
        delta.setTypedValue(new BigDecimal("-1000"));
        assertThat(after.getTypedValue()).isZero();
        assertThat(((EntityPicker<?>) UiTestUtils.getComponent(view, "contractField")).isReadOnly()).isTrue();
        ((JmixButton) UiTestUtils.getComponent(view, "saveAndCloseBtn")).click();
        assertThat(data.load(Addendum.class).id(a.getId()).one().getAmountAdjustment()).isEqualByComparingTo("-1000");
    }

    @Test void saveFromCorrectionTabRevealsInvalidGeneralFields() {
        navigators.detailView(UiTestUtils.getCurrentView(), Addendum.class)
                .withViewClass(AddendumDetailView.class).newEntity().navigate();
        AddendumDetailView view = UiTestUtils.getCurrentView();
        EntityPicker<Contract> contract = UiTestUtils.getComponent(view, "contractField");
        contract.setValue(fixture.contract);
        io.jmix.flowui.component.tabsheet.JmixTabSheet tabs = UiTestUtils.getComponent(view, "correctionsTabs");
        assertThat(tabs.getTabCount()).isEqualTo(3);
        tabs.setSelectedIndex(2);
        ((JmixButton) UiTestUtils.getComponent(view, "saveAndCloseBtn")).click();
        assertThat(tabs.getSelectedIndex()).isZero();
        assertThat(data.load(Addendum.class).id(view.getEditedEntity().getId()).optional()).isEmpty();
        view.closeWithDiscard();
    }

    @Test void tabsPreserveEditsAndCountUnsavedCorrections() {
        navigators.detailView(UiTestUtils.getCurrentView(), Addendum.class)
                .withViewClass(AddendumDetailView.class).newEntity().navigate();
        AddendumDetailView view = UiTestUtils.getCurrentView();
        TypedTextField<BigDecimal> delta = UiTestUtils.getComponent(view, "amountAdjustmentField");
        delta.setTypedValue(new BigDecimal("1234.56"));
        io.jmix.flowui.component.tabsheet.JmixTabSheet tabs = UiTestUtils.getComponent(view, "correctionsTabs");
        io.jmix.flowui.model.CollectionContainer<PaymentScheduleCorrection> corrections =
                io.jmix.flowui.view.ViewControllerUtils.getViewData(view).getContainer("paymentScheduleCorrectionsDc");
        var child = data.create(PaymentScheduleCorrection.class);
        child.setAddendum(view.getEditedEntity());
        corrections.getMutableItems().add(child);
        tabs.setSelectedIndex(2);
        assertThat(tabs.getTabAt(2).getLabel()).contains("(1)");
        corrections.getMutableItems().remove(child);
        assertThat(tabs.getTabAt(2).getLabel()).contains("(0)");
        tabs.setSelectedIndex(0);
        assertThat(delta.getTypedValue()).isEqualByComparingTo("1234.56");
        view.closeWithDiscard();
        assertThat(data.load(PaymentScheduleCorrection.class).id(child.getId()).optional()).isEmpty();
    }

    @Test void invalidCompositionSelectsItsTabAndBlocksParentSave() {
        var a = data.create(Addendum.class);
        a.setContract(fixture.contract); a.setNumber("invalid-child");
        a.setEffectiveDate(LocalDate.of(2026, 3, 1));
        navigators.detailView(UiTestUtils.getCurrentView(), Addendum.class)
                .withViewClass(AddendumDetailView.class).newEntity().navigate();
        AddendumDetailView view = UiTestUtils.getCurrentView();
        view.getEditedEntity().setContract(fixture.contract);
        view.getEditedEntity().setNumber("invalid-child");
        view.getEditedEntity().setEffectiveDate(LocalDate.of(2026, 3, 1));
        var context = io.jmix.flowui.view.ViewControllerUtils.getViewData(view).getDataContext();
        io.jmix.flowui.model.CollectionContainer<PaymentScheduleCorrection> corrections =
                io.jmix.flowui.view.ViewControllerUtils.getViewData(view).getContainer("paymentScheduleCorrectionsDc");
        var child = context.create(PaymentScheduleCorrection.class);
        child.setAddendum(view.getEditedEntity());
        corrections.getMutableItems().add(child); // Missing required project.
        assertThat(UiTestUtils.validateView(view).isEmpty()).isFalse();
        io.jmix.flowui.component.tabsheet.JmixTabSheet tabs = UiTestUtils.getComponent(view, "correctionsTabs");
        assertThat(tabs.getSelectedIndex()).isEqualTo(2);
        assertThat(data.load(Addendum.class).id(a.getId()).optional()).isEmpty();
        view.closeWithDiscard();
    }

    @Test void securityErasedProjectIsNotTreatedAsMissingInput() {
        navigators.detailView(UiTestUtils.getCurrentView(), Addendum.class)
                .withViewClass(AddendumDetailView.class).newEntity().navigate();
        AddendumDetailView view = UiTestUtils.getCurrentView();
        view.getEditedEntity().setContract(fixture.contract);
        view.getEditedEntity().setNumber("hidden-reference");
        view.getEditedEntity().setEffectiveDate(LocalDate.of(2026, 3, 1));
        var context = io.jmix.flowui.view.ViewControllerUtils.getViewData(view).getDataContext();
        io.jmix.flowui.model.CollectionContainer<PaymentScheduleCorrection> corrections =
                io.jmix.flowui.view.ViewControllerUtils.getViewData(view).getContainer("paymentScheduleCorrectionsDc");
        var child = context.create(PaymentScheduleCorrection.class);
        child.setAddendum(view.getEditedEntity());
        // Reproduce the state that Jmix row-level filtering supplies for an unreadable reference.
        io.jmix.core.entity.EntitySystemAccess.getSecurityState(child).addErasedId("project", fixture.project.getId());
        corrections.getMutableItems().add(child);
        assertThat(UiTestUtils.validateView(view).isEmpty()).isTrue();
        view.closeWithDiscard();
    }

    @Test void invalidCorrectionItemOpensItsEditorWithVisibleFieldError() {
        navigators.detailView(UiTestUtils.getCurrentView(), Addendum.class)
                .withViewClass(AddendumDetailView.class).newEntity().navigate();
        AddendumDetailView view = UiTestUtils.getCurrentView();
        view.getEditedEntity().setContract(fixture.contract);
        view.getEditedEntity().setNumber("invalid-item");
        view.getEditedEntity().setEffectiveDate(LocalDate.of(2026, 3, 1));
        var context = io.jmix.flowui.view.ViewControllerUtils.getViewData(view).getDataContext();
        io.jmix.flowui.model.CollectionContainer<PaymentScheduleCorrection> corrections =
                io.jmix.flowui.view.ViewControllerUtils.getViewData(view).getContainer("paymentScheduleCorrectionsDc");
        var correction = context.create(PaymentScheduleCorrection.class);
        correction.setAddendum(view.getEditedEntity());
        correction.setProject(fixture.project);
        var item = context.create(PaymentScheduleCorrectionItem.class);
        item.setCorrection(correction);
        item.setAmount(new BigDecimal("10.00"));
        correction.setItems(new java.util.ArrayList<>(java.util.List.of(item)));
        corrections.getMutableItems().add(correction);
        assertThat(UiTestUtils.validateView(view).isEmpty()).isFalse();
        var editor = dialogs.getOpenedDialogWindows().getDialogs().stream()
                .filter(v -> v instanceof ru.stnovator.finassist.view.paymentschedulecorrectionitem.PaymentScheduleCorrectionItemDetailView)
                .findFirst().orElseThrow();
        TypedDatePicker<LocalDate> date = UiTestUtils.getComponent(editor, "itemDateField");
        assertThat(date.isInvalid()).isTrue();
        assertThat(io.jmix.flowui.view.ViewControllerUtils.getViewData(editor).getDataContext().getParent()).isNotNull();
        for (var dialog : new java.util.ArrayList<>(dialogs.getOpenedDialogWindows().getDialogs()).reversed()) {
            ((io.jmix.flowui.view.StandardDetailView<?>) dialog).closeWithDiscard();
        }
        view.closeWithDiscard();
    }
    @Test void comparisonTotalsReactToUnsavedItemChangesAndRemoval() {
        auth.runWithUser("admin", () -> fixture.payment(LocalDate.of(2026, 4, 1), "100.10"));
        navigators.detailView(UiTestUtils.getCurrentView(), Addendum.class)
                .withViewClass(AddendumDetailView.class).newEntity().navigate();
        AddendumDetailView view = UiTestUtils.getCurrentView();
        view.getEditedEntity().setContract(fixture.contract);
        view.getEditedEntity().setNumber("comparison-draft");
        view.getEditedEntity().setEffectiveDate(LocalDate.of(2026, 3, 1));
        var context = io.jmix.flowui.view.ViewControllerUtils.getViewData(view).getDataContext();
        io.jmix.flowui.model.CollectionContainer<PaymentScheduleCorrection> corrections =
                io.jmix.flowui.view.ViewControllerUtils.getViewData(view).getContainer("paymentScheduleCorrectionsDc");
        var correction = context.create(PaymentScheduleCorrection.class);
        correction.setAddendum(view.getEditedEntity()); correction.setProject(fixture.project);
        var item = context.create(PaymentScheduleCorrectionItem.class);
        item.setCorrection(correction); item.setItemDate(LocalDate.of(2026, 4, 1)); item.setAmount(new BigDecimal("120.20"));
        correction.setItems(new java.util.ArrayList<>(java.util.List.of(item)));
        corrections.getMutableItems().add(correction);
        io.jmix.flowui.component.grid.DataGrid<PaymentScheduleCorrection> grid = UiTestUtils.getComponent(view, "paymentScheduleCorrectionsDataGrid");
        com.vaadin.flow.component.UI.getCurrent().getInternals().getStateTree().runExecutionsBeforeClientResponse();
        assertThat(grid.getColumnByKey("createdDate")).isNull();
        assertThat(grid.getColumns()).hasSize(5);
        assertThat(grid.getFooterRows().getFirst().getCell(grid.getColumnByKey("comparisonDelta")).getText()).isEqualTo("+20,10");
        item.setAmount(new BigDecimal("100.11"));
        com.vaadin.flow.component.UI.getCurrent().getInternals().getStateTree().runExecutionsBeforeClientResponse();
        assertThat(grid.getFooterRows().getFirst().getCell(grid.getColumnByKey("comparisonDelta")).getText()).isEqualTo("+0,01");
        corrections.getMutableItems().remove(correction);
        com.vaadin.flow.component.UI.getCurrent().getInternals().getStateTree().runExecutionsBeforeClientResponse();
        assertThat(grid.getFooterRows().getFirst().getCell(grid.getColumnByKey("comparisonDelta")).getText()).isEqualTo("—");
        view.closeWithDiscard();
        assertThat(data.load(PaymentScheduleCorrectionItem.class).id(item.getId()).optional()).isEmpty();
    }

    @Test void generalComparisonUsesHistoricalEndDateAndExplainsUnchangedResults() {
        var earlier = data.create(Addendum.class);
        earlier.setContract(fixture.contract); earlier.setNumber("earlier-end");
        earlier.setEffectiveDate(LocalDate.of(2026, 2, 1)); earlier.setNewEndDate(LocalDate.of(2027, 6, 30));
        fixture.save(earlier);
        navigators.detailView(UiTestUtils.getCurrentView(), Addendum.class)
                .withViewClass(AddendumDetailView.class).newEntity().navigate();
        AddendumDetailView view = UiTestUtils.getCurrentView();
        var draft = view.getEditedEntity();
        draft.setContract(fixture.contract); draft.setEffectiveDate(LocalDate.of(2026, 3, 1));
        TypedDatePicker<LocalDate> before = UiTestUtils.getComponent(view, "endDateBeforeField");
        TypedDatePicker<LocalDate> after = UiTestUtils.getComponent(view, "endDateAfterField");
        TypedTextField<BigDecimal> amountAfter = UiTestUtils.getComponent(view, "amountAfterField");
        assertThat(before.isReadOnly()).isTrue();
        assertThat(before.getValue()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(after.getValue()).isEqualTo(before.getValue());
        assertThat(after.getHelperText()).isEqualTo("Без изменения");
        assertThat(amountAfter.getHelperText()).isEqualTo("Без изменения");
        draft.setNewEndDate(LocalDate.of(2028, 1, 1));
        assertThat(after.getValue()).isEqualTo(LocalDate.of(2028, 1, 1));
        assertThat(after.getHelperText()).isEmpty();
        TypedTextField<BigDecimal> adjustment = UiTestUtils.getComponent(view, "amountAdjustmentField");
        adjustment.setInvalid(true);
        draft.setEffectiveDate(LocalDate.of(2026, 1, 15));
        assertThat(before.getValue()).isEqualTo(fixture.contract.getEndDate());
        assertThat(after.getValue()).isNull();
        assertThat(after.getHelperText()).isEmpty();
        draft.setEffectiveDate(null);
        assertThat(before.getValue()).isNull();
        assertThat(after.getValue()).isNull();
        assertThat(amountAfter.getHelperText()).isEmpty();
        view.closeWithDiscard();
    }

}
