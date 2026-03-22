package ru.stnovator.finassist.view.shipmentschedule;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.router.Route;
import io.jmix.core.Sort;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionChangeType;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.InstanceContainer;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import io.jmix.flowui.view.ViewComponent;
import ru.stnovator.finassist.entity.ShipmentSchedule;
import ru.stnovator.finassist.entity.ShipmentScheduleItem;
import ru.stnovator.finassist.view.main.MainView;

import java.math.BigDecimal;

@Route(value = "shipment-schedules/:id", layout = MainView.class)
@ViewController("ShipmentSchedule.detail")
@ViewDescriptor("shipment-schedule-detail-view.xml")
@EditedEntityContainer("shipmentScheduleDc")
public class ShipmentScheduleDetailView extends StandardDetailView<ShipmentSchedule> {
    @ViewComponent
    private CollectionContainer<ShipmentScheduleItem> shipmentScheduleItemsDc;
    @ViewComponent
    private DataGrid<ShipmentScheduleItem> shipmentScheduleItemsDataGrid;
    @ViewComponent
    private MessageBundle messageBundle;

    private final DatatypeFormatter datatypeFormatter;

    private boolean sortingInProgress;

    public ShipmentScheduleDetailView(DatatypeFormatter datatypeFormatter) {
        this.datatypeFormatter = datatypeFormatter;
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        updateGridTotal();
    }

    @Subscribe(id = "shipmentScheduleItemsDc", target = Target.DATA_CONTAINER)
    public void onShipmentScheduleItemsDcCollectionChange(
            final CollectionContainer.CollectionChangeEvent<ShipmentScheduleItem> event) {
        updateGridTotal();
        if (event.getChangeType() != CollectionChangeType.ADD_ITEMS) {
            return;
        }
        sortItemsInMemory();
    }

    @Subscribe(id = "shipmentScheduleItemsDc", target = Target.DATA_CONTAINER)
    public void onShipmentScheduleItemsDcItemPropertyChange(
            final InstanceContainer.ItemPropertyChangeEvent<ShipmentScheduleItem> event) {
        if ("itemDate".equals(event.getProperty())) {
            sortItemsInMemory();
        }
        if ("amount".equals(event.getProperty())) {
            updateGridTotal();
        }
    }

    private void sortItemsInMemory() {
        if (sortingInProgress) {
            return;
        }
        var sorter = shipmentScheduleItemsDc.getSorter();
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

    private void updateGridTotal() {
        Grid.Column<ShipmentScheduleItem> amountColumn = shipmentScheduleItemsDataGrid.getColumnByKey("amount");
        if (amountColumn == null) {
            return;
        }
        amountColumn.setFooter(messageBundle.getMessage("ShipmentScheduleDetailView.total")
                + " " + datatypeFormatter.formatBigDecimal(sumItemsAmount()));
    }

    private BigDecimal sumItemsAmount() {
        return shipmentScheduleItemsDc.getItems().stream()
                .map(ShipmentScheduleItem::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
