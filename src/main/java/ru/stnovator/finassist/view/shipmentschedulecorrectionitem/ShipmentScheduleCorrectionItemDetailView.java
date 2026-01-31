package ru.stnovator.finassist.view.shipmentschedulecorrectionitem;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.ShipmentScheduleCorrectionItem;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "shipment-schedule-correction-items/:id", layout = MainView.class)
@ViewController("ShipmentScheduleCorrectionItem.detail")
@ViewDescriptor("shipment-schedule-correction-item-detail-view.xml")
@EditedEntityContainer("shipmentScheduleCorrectionItemDc")
public class ShipmentScheduleCorrectionItemDetailView extends StandardDetailView<ShipmentScheduleCorrectionItem> {
}
