package ru.stnovator.finassist.view.shipmentschedulecorrection;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.ShipmentScheduleCorrection;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "shipment-schedule-corrections", layout = MainView.class)
@ViewController("ShipmentScheduleCorrection.list")
@ViewDescriptor("shipment-schedule-correction-list-view.xml")
@LookupComponent("shipmentScheduleCorrectionsDataGrid")
@DialogMode(width = "64em")
public class ShipmentScheduleCorrectionListView extends StandardListView<ShipmentScheduleCorrection> {
}
