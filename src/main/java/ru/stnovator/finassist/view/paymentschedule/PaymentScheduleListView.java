package ru.stnovator.finassist.view.paymentschedule;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.PaymentSchedule;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "payment-schedules", layout = MainView.class)
@ViewController("PaymentSchedule.list")
@ViewDescriptor("payment-schedule-list-view.xml")
@LookupComponent("paymentSchedulesDataGrid")
@DialogMode(width = "64em")
public class PaymentScheduleListView extends StandardListView<PaymentSchedule> {
}
