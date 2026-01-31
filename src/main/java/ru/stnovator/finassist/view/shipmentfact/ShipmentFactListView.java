package ru.stnovator.finassist.view.shipmentfact;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.ShipmentFact;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "shipment-facts", layout = MainView.class)
@ViewController("ShipmentFact.list")
@ViewDescriptor("shipment-fact-list-view.xml")
@LookupComponent("shipmentFactsDataGrid")
@DialogMode(width = "64em")
public class ShipmentFactListView extends StandardListView<ShipmentFact> {
}
