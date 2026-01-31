package ru.stnovator.finassist.view.paymentschedulecorrection;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.PaymentScheduleCorrection;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "payment-schedule-corrections", layout = MainView.class)
@ViewController("PaymentScheduleCorrection.list")
@ViewDescriptor("payment-schedule-correction-list-view.xml")
@LookupComponent("paymentScheduleCorrectionsDataGrid")
@DialogMode(width = "64em")
public class PaymentScheduleCorrectionListView extends StandardListView<PaymentScheduleCorrection> {
}
