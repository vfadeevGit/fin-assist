package ru.stnovator.finassist.view.customer;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.*;
import ru.stnovator.finassist.entity.Customer;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "customers", layout = MainView.class)
@ViewController("Customer.list")
@ViewDescriptor("customer-list-view.xml")
@LookupComponent("customersDataGrid")
@DialogMode(width = "64em")
public class CustomerListView extends StandardListView<Customer> {
    @ViewComponent
    private JmixButton selectBtn;

    @Subscribe
    public void onInit(final InitEvent event) {
//        JmixButton.setElement();
    }
}
