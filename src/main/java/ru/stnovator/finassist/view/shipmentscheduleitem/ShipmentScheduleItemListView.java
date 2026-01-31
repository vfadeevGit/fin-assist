package ru.stnovator.finassist.view.shipmentscheduleitem;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.ShipmentScheduleItem;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "shipment-schedule-items", layout = MainView.class)
@ViewController("ShipmentScheduleItem.list")
@ViewDescriptor("shipment-schedule-item-list-view.xml")
@LookupComponent("shipmentScheduleItemsDataGrid")
@DialogMode(width = "64em")
public class ShipmentScheduleItemListView extends StandardListView<ShipmentScheduleItem> {
}
