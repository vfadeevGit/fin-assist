package ru.stnovator.finassist.view.shipmentscheduleitem;

import ru.stnovator.finassist.view.support.DetailViewPresentation;
import io.jmix.flowui.view.DialogMode;
import org.springframework.beans.factory.annotation.Autowired;
import io.jmix.flowui.view.Subscribe;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.ShipmentScheduleItem;
import ru.stnovator.finassist.view.main.MainView;

@DialogMode(width = "30rem", height = "min(22rem, calc(100dvh - 6rem))", maxWidth = "calc(100vw - 2rem)", keepInViewport = true, resizable = true)
@Route(value = "shipment-schedule-items/:id", layout = MainView.class)
@ViewController("ShipmentScheduleItem.detail")
@ViewDescriptor("shipment-schedule-item-detail-view.xml")
@EditedEntityContainer("shipmentScheduleItemDc")
public class ShipmentScheduleItemDetailView extends StandardDetailView<ShipmentScheduleItem> {
    @Autowired
    private DetailViewPresentation detailViewPresentation;

    @Subscribe
    public void onPresentationReady(final ReadyEvent event) {
        detailViewPresentation.initialize(this);
    }

}
