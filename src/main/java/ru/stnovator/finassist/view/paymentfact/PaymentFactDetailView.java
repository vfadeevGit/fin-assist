package ru.stnovator.finassist.view.paymentfact;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.PaymentFact;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "payment-facts/:id", layout = MainView.class)
@ViewController("PaymentFact.detail")
@ViewDescriptor("payment-fact-detail-view.xml")
@EditedEntityContainer("paymentFactDc")
public class PaymentFactDetailView extends StandardDetailView<PaymentFact> {
}
