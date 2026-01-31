package ru.stnovator.finassist.view.paymentscheduleitem;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.PaymentScheduleItem;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "payment-schedule-items/:id", layout = MainView.class)
@ViewController("PaymentScheduleItem.detail")
@ViewDescriptor("payment-schedule-item-detail-view.xml")
@EditedEntityContainer("paymentScheduleItemDc")
public class PaymentScheduleItemDetailView extends StandardDetailView<PaymentScheduleItem> {
}
