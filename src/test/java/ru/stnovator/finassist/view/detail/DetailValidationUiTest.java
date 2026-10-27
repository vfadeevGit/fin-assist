package ru.stnovator.finassist.view.detail;

import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.valuepicker.EntityPicker;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.FinAssistApplication;
import ru.stnovator.finassist.entity.Addendum;
import ru.stnovator.finassist.view.addendum.AddendumDetailView;
import static org.assertj.core.api.Assertions.assertThat;

@UiTest
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class DetailValidationUiTest {
    @Autowired ViewNavigators viewNavigators;
    @Autowired io.jmix.core.DataManager dataManager;

    @Test
    void pristineAddendumIsNeutralButSaveStillValidates() {
        viewNavigators.detailView(UiTestUtils.getCurrentView(), Addendum.class)
                .withViewClass(AddendumDetailView.class).newEntity().navigate();
        AddendumDetailView view = UiTestUtils.getCurrentView();
        EntityPicker<?> contract = (EntityPicker<?>) UiComponentUtils.getComponent(view, "contractField");
        assertThat(contract.isRequiredIndicatorVisible()).isTrue();
        assertThat(contract.isInvalid()).as("untouched required field").isFalse();
        ((JmixButton) UiComponentUtils.getComponent(view, "saveAndCloseBtn")).click();
        assertThat((Object) UiTestUtils.getCurrentView()).isSameAs(view);
        assertThat(contract.isInvalid()).as("required constraint on save").isTrue();
    }

    static Stream<Object[]> detailViews() {
        return Stream.of(
                new Object[]{ru.stnovator.finassist.entity.Addendum.class, ru.stnovator.finassist.view.addendum.AddendumDetailView.class},
                new Object[]{ru.stnovator.finassist.entity.Contract.class, ru.stnovator.finassist.view.contract.ContractDetailView.class},
                new Object[]{ru.stnovator.finassist.entity.Customer.class, ru.stnovator.finassist.view.customer.CustomerDetailView.class},
                new Object[]{ru.stnovator.finassist.entity.LineOfBusiness.class, ru.stnovator.finassist.view.lineofbusiness.LineOfBusinessDetailView.class},
                new Object[]{ru.stnovator.finassist.entity.PaymentSchedule.class, ru.stnovator.finassist.view.paymentschedule.PaymentScheduleDetailView.class},
                new Object[]{ru.stnovator.finassist.entity.PaymentScheduleCorrection.class, ru.stnovator.finassist.view.paymentschedulecorrection.PaymentScheduleCorrectionDetailView.class},
                new Object[]{ru.stnovator.finassist.entity.PaymentScheduleCorrectionItem.class, ru.stnovator.finassist.view.paymentschedulecorrectionitem.PaymentScheduleCorrectionItemDetailView.class},
                new Object[]{ru.stnovator.finassist.entity.PaymentScheduleItem.class, ru.stnovator.finassist.view.paymentscheduleitem.PaymentScheduleItemDetailView.class},
                new Object[]{ru.stnovator.finassist.entity.Project.class, ru.stnovator.finassist.view.project.ProjectDetailView.class},
                new Object[]{ru.stnovator.finassist.entity.ShipmentSchedule.class, ru.stnovator.finassist.view.shipmentschedule.ShipmentScheduleDetailView.class},
                new Object[]{ru.stnovator.finassist.entity.ShipmentScheduleCorrection.class, ru.stnovator.finassist.view.shipmentschedulecorrection.ShipmentScheduleCorrectionDetailView.class},
                new Object[]{ru.stnovator.finassist.entity.ShipmentScheduleCorrectionItem.class, ru.stnovator.finassist.view.shipmentschedulecorrectionitem.ShipmentScheduleCorrectionItemDetailView.class},
                new Object[]{ru.stnovator.finassist.entity.ShipmentScheduleItem.class, ru.stnovator.finassist.view.shipmentscheduleitem.ShipmentScheduleItemDetailView.class},
                new Object[]{ru.stnovator.finassist.entity.User.class, ru.stnovator.finassist.view.user.UserDetailView.class});
    }

    @ParameterizedTest
    @MethodSource("detailViews")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void opensEveryEditorWithNeutralRequiredFields(Class entityClass, Class viewClass) {
        viewNavigators.detailView(UiTestUtils.getCurrentView(), entityClass)
                .withViewClass(viewClass).newEntity().navigate();
        io.jmix.flowui.view.View<?> view = UiTestUtils.getCurrentView();
        assertThat(view).isInstanceOf(viewClass);
        assertThat(UiComponentUtils.getComponent(view, "detailScroller")).isNotNull();
        for (com.vaadin.flow.component.Component component : UiComponentUtils.getComponents(view)) {
            if (component instanceof com.vaadin.flow.component.HasValidation field) {
                assertThat(field.isInvalid()).as("pristine field %s in %s", component.getId(), viewClass).isFalse();
            }
        }
        if (viewClass.getSimpleName().endsWith("CorrectionDetailView")) {
            io.jmix.flowui.component.combobox.EntityComboBox<ru.stnovator.finassist.entity.Project> projectField =
                    UiTestUtils.getComponent(view, "projectField");
            ru.stnovator.finassist.entity.Project project = dataManager.create(ru.stnovator.finassist.entity.Project.class);
            project.setName("Long project identity that must remain readable on a narrow screen");
            projectField.setValue(project);
            assertThat(projectField.getHelperText()).contains(project.getName());
        }
        if (viewClass.getSimpleName().endsWith("CorrectionItemDetailView")) {
            assertThat(UiComponentUtils.getComponent(view, "parentContextHint").isVisible()).isTrue();
            assertThat(((JmixButton) UiComponentUtils.getComponent(view, "saveAndCloseBtn")).isEnabled()).isFalse();
        }
    }
}
