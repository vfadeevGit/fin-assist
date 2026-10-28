package ru.stnovator.finassist.view.shipmentschedulecorrectionitem;

import ru.stnovator.finassist.view.support.DetailViewPresentation;
import io.jmix.flowui.view.DialogMode;
import org.springframework.beans.factory.annotation.Autowired;
import io.jmix.flowui.view.Subscribe;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.ShipmentScheduleCorrectionItem;
import ru.stnovator.finassist.view.main.MainView;

@DialogMode(width = "30rem", height = "min(28rem, calc(100dvh - 6rem))", maxWidth = "calc(100vw - 2rem)", keepInViewport = true, resizable = true)
@Route(value = "shipment-schedule-correction-items/:id", layout = MainView.class)
@ViewController("ShipmentScheduleCorrectionItem.detail")
@ViewDescriptor("shipment-schedule-correction-item-detail-view.xml")
@EditedEntityContainer("shipmentScheduleCorrectionItemDc")
public class ShipmentScheduleCorrectionItemDetailView extends StandardDetailView<ShipmentScheduleCorrectionItem> {
    @Autowired
    private DetailViewPresentation detailViewPresentation;

    @Subscribe
    public void onPresentationReady(final ReadyEvent event) {
        detailViewPresentation.initialize(this);
    }

    @Subscribe
    public void onParentContextValidation(final ValidationEvent event) {
        if (getEditedEntity().getCorrection() == null) {
            event.getErrors().add(detailMessages.getMessage("ru.stnovator.finassist.view.detail", "parentRequired"));
        }
    }

    @Autowired
    private io.jmix.core.Messages detailMessages;

}
