package ru.stnovator.finassist.view.shipmentfact;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.ShipmentFact;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "shipment-facts/:id", layout = MainView.class)
@ViewController("ShipmentFact.detail")
@ViewDescriptor("shipment-fact-detail-view.xml")
@EditedEntityContainer("shipmentFactDc")
public class ShipmentFactDetailView extends StandardDetailView<ShipmentFact> {
}
