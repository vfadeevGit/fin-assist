package ru.stnovator.finassist.view.paymentschedulecorrection;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.PaymentScheduleCorrection;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "payment-schedule-corrections/:id", layout = MainView.class)
@ViewController("PaymentScheduleCorrection.detail")
@ViewDescriptor("payment-schedule-correction-detail-view.xml")
@EditedEntityContainer("paymentScheduleCorrectionDc")
public class PaymentScheduleCorrectionDetailView extends StandardDetailView<PaymentScheduleCorrection> {
}
