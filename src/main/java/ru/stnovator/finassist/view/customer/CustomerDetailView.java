package ru.stnovator.finassist.view.customer;


import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.router.Route;
import io.jmix.core.entity.EntityValues;
import io.jmix.flowui.view.*;
import ru.stnovator.finassist.entity.Customer;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "customers/:id", layout = MainView.class)
@ViewController("Customer.detail")
@ViewDescriptor("customer-detail-view.xml")
@EditedEntityContainer("customerDc")
public class CustomerDetailView extends StandardDetailView<Customer> {
    @ViewComponent
    private NativeLabel CategoryName;

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        String categoryName = EntityValues.getValue(getEditedEntity(), "+customerPropertiesCategoryName");
        CategoryName.setText(categoryName);
        System.out.println(categoryName);
    }

}