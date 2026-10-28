package ru.stnovator.finassist.view.customer;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.*;
import ru.stnovator.finassist.entity.Customer;
import ru.stnovator.finassist.view.main.MainView;
import ru.stnovator.finassist.view.support.RelatedListView;
import ru.stnovator.finassist.view.support.RelatedRecordsPresentation;

@Route(value = "customers", layout = MainView.class)
@ViewController("Customer.list")
@ViewDescriptor("customer-list-view.xml")
@LookupComponent("customersDataGrid")
@DialogMode(width = "64em")
public class CustomerListView extends RelatedListView<Customer> {
    @Override protected String relatedPrefix() { return "customers"; }
    @Override protected RelatedRecordsPresentation.Kind relatedKind() { return RelatedRecordsPresentation.Kind.CONTRACTS; }
}
