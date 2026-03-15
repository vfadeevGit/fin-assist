package ru.stnovator.finassist.view.shipmentschedulecorrection;

import com.vaadin.flow.router.Route;
import io.jmix.core.Sort;
import io.jmix.flowui.model.CollectionChangeType;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.InstanceContainer;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import io.jmix.flowui.view.ViewComponent;
import ru.stnovator.finassist.entity.ShipmentScheduleCorrection;
import ru.stnovator.finassist.entity.ShipmentScheduleCorrectionItem;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "shipment-schedule-corrections/:id", layout = MainView.class)
@ViewController("ShipmentScheduleCorrection.detail")
@ViewDescriptor("shipment-schedule-correction-detail-view.xml")
@EditedEntityContainer("shipmentScheduleCorrectionDc")
public class ShipmentScheduleCorrectionDetailView extends StandardDetailView<ShipmentScheduleCorrection> {
    @ViewComponent
    private CollectionContainer<ShipmentScheduleCorrectionItem> shipmentScheduleCorrectionItemsDc;

    private boolean sortingInProgress;

    @Subscribe(id = "shipmentScheduleCorrectionItemsDc", target = Target.DATA_CONTAINER)
    public void onShipmentScheduleCorrectionItemsDcCollectionChange(
            final CollectionContainer.CollectionChangeEvent<ShipmentScheduleCorrectionItem> event) {
        if (event.getChangeType() != CollectionChangeType.ADD_ITEMS) {
            return;
        }
        sortItemsInMemory();
    }

    @Subscribe(id = "shipmentScheduleCorrectionItemsDc", target = Target.DATA_CONTAINER)
    public void onShipmentScheduleCorrectionItemsDcItemPropertyChange(
            final InstanceContainer.ItemPropertyChangeEvent<ShipmentScheduleCorrectionItem> event) {
        if ("itemDate".equals(event.getProperty())) {
            sortItemsInMemory();
        }
    }

    private void sortItemsInMemory() {
        if (sortingInProgress) {
            return;
        }
        var sorter = shipmentScheduleCorrectionItemsDc.getSorter();
        if (sorter == null) {
            return;
        }
        sortingInProgress = true;
        try {
            sorter.sort(Sort.by(Sort.Direction.ASC, "itemDate"));
        } finally {
            sortingInProgress = false;
        }
    }
}
