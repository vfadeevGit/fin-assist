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
}
