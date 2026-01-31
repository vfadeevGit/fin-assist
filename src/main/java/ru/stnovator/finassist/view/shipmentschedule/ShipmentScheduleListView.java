package ru.stnovator.finassist.view.shipmentschedule;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.ShipmentSchedule;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "shipment-schedules", layout = MainView.class)
@ViewController("ShipmentSchedule.list")
@ViewDescriptor("shipment-schedule-list-view.xml")
@LookupComponent("shipmentSchedulesDataGrid")
@DialogMode(width = "64em")
public class ShipmentScheduleListView extends StandardListView<ShipmentSchedule> {
}
