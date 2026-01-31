package ru.stnovator.finassist.view.paymentfact;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.PaymentFact;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "payment-facts", layout = MainView.class)
@ViewController("PaymentFact.list")
@ViewDescriptor("payment-fact-list-view.xml")
@LookupComponent("paymentFactsDataGrid")
@DialogMode(width = "64em")
public class PaymentFactListView extends StandardListView<PaymentFact> {
}
