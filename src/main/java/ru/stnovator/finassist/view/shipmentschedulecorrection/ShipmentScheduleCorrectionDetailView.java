package ru.stnovator.finassist.view.shipmentschedulecorrection;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.ShipmentScheduleCorrection;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "shipment-schedule-corrections/:id", layout = MainView.class)
@ViewController("ShipmentScheduleCorrection.detail")
@ViewDescriptor("shipment-schedule-correction-detail-view.xml")
@EditedEntityContainer("shipmentScheduleCorrectionDc")
public class ShipmentScheduleCorrectionDetailView extends StandardDetailView<ShipmentScheduleCorrection> {
}
