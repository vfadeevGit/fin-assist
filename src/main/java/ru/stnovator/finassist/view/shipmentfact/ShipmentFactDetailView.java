package ru.stnovator.finassist.view.shipmentfact;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.router.Route;
import io.jmix.core.DataManager;
import io.jmix.flowui.component.combobox.EntityComboBox;
import io.jmix.flowui.component.valuepicker.EntityPicker;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.model.DataContext;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.entity.Customer;
import ru.stnovator.finassist.entity.Project;
import ru.stnovator.finassist.entity.ShipmentFact;
import ru.stnovator.finassist.view.main.MainView;

import java.time.LocalDate;
import java.util.Objects;

@Route(value = "shipment-facts/:id", layout = MainView.class)
@ViewController("ShipmentFact.detail")
@ViewDescriptor("shipment-fact-detail-view.xml")
@EditedEntityContainer("shipmentFactDc")
public class ShipmentFactDetailView extends StandardDetailView<ShipmentFact> {
    @ViewComponent
    private EntityPicker<Customer> customerField;
    @ViewComponent
    private EntityComboBox<Contract> contractField;
    @ViewComponent
    private EntityComboBox<Project> projectField;

    @ViewComponent
    private CollectionLoader<Customer> customersDl;
    @ViewComponent
    private CollectionLoader<Contract> contractsDl;
    @ViewComponent
    private CollectionLoader<Project> projectsDl;

    @ViewComponent
    private DataContext dataContext;

    @Autowired
    private DataManager dataManager;

    private boolean updatingProject;

    @Subscribe
    public void onInit(final InitEvent event) {
        contractsDl.setParameter("customer", null);
        projectsDl.setParameter("contract", null);
        contractField.setEnabled(false);
        projectField.setEnabled(false);
    }

    @Subscribe
    public void onInitEntity(final InitEntityEvent<ShipmentFact> event) {
        event.getEntity().setShipmentDate(LocalDate.now());
    }

    @Subscribe("customerField")
    public void onCustomerFieldValueChange(
            final AbstractField.ComponentValueChangeEvent<EntityPicker<Customer>, Customer> event) {
        if (!event.isFromClient()) {
            return;
        }
        Customer customer = event.getValue();
        clearContractAndProject();
        contractsDl.setParameter("customer", customer);
        contractsDl.load();
        contractField.setEnabled(customer != null);
        if (customer == null) {
            projectField.setEnabled(false);
        }
    }

    @Subscribe("contractField")
    public void onContractFieldValueChange(
            final AbstractField.ComponentValueChangeEvent<EntityComboBox<Contract>, Contract> event) {
        if (!event.isFromClient()) {
            return;
        }
        Contract contract = event.getValue();
        clearProject();
        if (contract != null) {
            Contract reloaded = dataManager.load(Contract.class).id(contract.getId()).one();
            if (!Objects.equals(reloaded, contractField.getValue())) {
                contractField.setValue(reloaded);
            }
            contract = reloaded;
        }
        projectsDl.setParameter("contract", contract);
        projectsDl.load();
        projectField.setEnabled(contract != null);
    }

    @Subscribe("projectField")
    public void onProjectFieldValueChange(
            final AbstractField.ComponentValueChangeEvent<EntityComboBox<Project>, Project> event) {
        if (!event.isFromClient() || updatingProject) {
            return;
        }
        Project project = event.getValue();
        if (project == null) {
            return;
        }
        updatingProject = true;
        try {
            Project reloaded = dataManager.load(Project.class).id(project.getId()).one();
            Project mergedProject = dataContext.merge(reloaded);
            projectField.setValue(mergedProject);
        } finally {
            updatingProject = false;
        }
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        ShipmentFact shipmentFact = getEditedEntity();
        Contract contract = shipmentFact.getContract();
        Customer customer = contract != null ? contract.getCustomer() : null;
        if (!Objects.equals(customerField.getValue(), customer)) {
            customerField.setValue(customer);
        }
        if (customer != null) {
            contractsDl.setParameter("customer", customer);
            contractsDl.load();
            contractField.setEnabled(true);
        }
        if (contract != null) {
            projectsDl.setParameter("contract", contract);
            projectsDl.load();
            projectField.setEnabled(true);
        }
    }

    private void clearContractAndProject() {
        if (contractField.getValue() != null) {
            contractField.clear();
        }
        clearProject();
    }

    private void clearProject() {
        if (projectField.getValue() != null) {
            projectField.clear();
        }
    }
}
