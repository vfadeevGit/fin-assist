package ru.stnovator.finassist.view.shipmentschedule;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.ShipmentSchedule;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "shipment-schedules/:id", layout = MainView.class)
@ViewController("ShipmentSchedule.detail")
@ViewDescriptor("shipment-schedule-detail-view.xml")
@EditedEntityContainer("shipmentScheduleDc")
public class ShipmentScheduleDetailView extends StandardDetailView<ShipmentSchedule> {
}
