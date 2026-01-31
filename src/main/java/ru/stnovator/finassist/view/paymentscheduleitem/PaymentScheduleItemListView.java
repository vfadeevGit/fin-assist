package ru.stnovator.finassist.view.paymentscheduleitem;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.PaymentScheduleItem;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "payment-schedule-items", layout = MainView.class)
@ViewController("PaymentScheduleItem.list")
@ViewDescriptor("payment-schedule-item-list-view.xml")
@LookupComponent("paymentScheduleItemsDataGrid")
@DialogMode(width = "64em")
public class PaymentScheduleItemListView extends StandardListView<PaymentScheduleItem> {
}
