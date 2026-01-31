package ru.stnovator.finassist.view.paymentschedulecorrectionitem;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.PaymentScheduleCorrectionItem;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "payment-schedule-correction-items", layout = MainView.class)
@ViewController("PaymentScheduleCorrectionItem.list")
@ViewDescriptor("payment-schedule-correction-item-list-view.xml")
@LookupComponent("paymentScheduleCorrectionItemsDataGrid")
@DialogMode(width = "64em")
public class PaymentScheduleCorrectionItemListView extends StandardListView<PaymentScheduleCorrectionItem> {
}
