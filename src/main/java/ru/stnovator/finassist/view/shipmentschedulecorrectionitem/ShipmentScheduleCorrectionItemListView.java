package ru.stnovator.finassist.view.shipmentschedulecorrectionitem;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.ShipmentScheduleCorrectionItem;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "shipment-schedule-correction-items", layout = MainView.class)
@ViewController("ShipmentScheduleCorrectionItem.list")
@ViewDescriptor("shipment-schedule-correction-item-list-view.xml")
@LookupComponent("shipmentScheduleCorrectionItemsDataGrid")
@DialogMode(width = "64em")
public class ShipmentScheduleCorrectionItemListView extends StandardListView<ShipmentScheduleCorrectionItem> {
}
