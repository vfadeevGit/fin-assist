package ru.stnovator.finassist.view.customer;


import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.*;
import ru.stnovator.finassist.entity.Customer;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "customers/:id", layout = MainView.class)
@ViewController("Customer.detail")
@ViewDescriptor("customer-detail-view.xml")
@EditedEntityContainer("customerDc")
public class CustomerDetailView extends StandardDetailView<Customer> {

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {

    }

}