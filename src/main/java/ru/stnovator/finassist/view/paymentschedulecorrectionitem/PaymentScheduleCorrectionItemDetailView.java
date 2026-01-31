package ru.stnovator.finassist.view.paymentschedulecorrectionitem;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.PaymentScheduleCorrectionItem;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "payment-schedule-correction-items/:id", layout = MainView.class)
@ViewController("PaymentScheduleCorrectionItem.detail")
@ViewDescriptor("payment-schedule-correction-item-detail-view.xml")
@EditedEntityContainer("paymentScheduleCorrectionItemDc")
public class PaymentScheduleCorrectionItemDetailView extends StandardDetailView<PaymentScheduleCorrectionItem> {
}
