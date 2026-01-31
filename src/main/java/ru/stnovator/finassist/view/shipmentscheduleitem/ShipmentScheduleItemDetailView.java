package ru.stnovator.finassist.view.shipmentscheduleitem;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.ShipmentScheduleItem;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "shipment-schedule-items/:id", layout = MainView.class)
@ViewController("ShipmentScheduleItem.detail")
@ViewDescriptor("shipment-schedule-item-detail-view.xml")
@EditedEntityContainer("shipmentScheduleItemDc")
public class ShipmentScheduleItemDetailView extends StandardDetailView<ShipmentScheduleItem> {
}
