package ru.stnovator.finassist.view.project;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.component.tabs.Tab;
import io.jmix.core.Sort;
import io.jmix.flowui.component.tabsheet.JmixTabSheet;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionChangeType;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.DataContext;
import io.jmix.flowui.model.InstanceContainer;
import io.jmix.flowui.view.*;
import ru.stnovator.finassist.entity.PaymentSchedule;
import ru.stnovator.finassist.entity.PaymentScheduleItem;
import ru.stnovator.finassist.entity.Project;
import ru.stnovator.finassist.entity.ShipmentSchedule;
import ru.stnovator.finassist.entity.ShipmentScheduleItem;
import ru.stnovator.finassist.view.main.MainView;

import java.math.BigDecimal;
import java.util.ArrayList;

@Route(value = "projects/:id", layout = MainView.class)
@ViewController("Project.detail")
@ViewDescriptor("project-detail-view.xml")
@EditedEntityContainer("projectDc")
public class ProjectDetailView extends StandardDetailView<Project> {

    @ViewComponent
    private DataContext dataContext;
    @ViewComponent
    private CollectionContainer<ShipmentScheduleItem> shipmentScheduleItemsDc;
    @ViewComponent
    private CollectionContainer<PaymentScheduleItem> paymentScheduleItemsDc;

    @ViewComponent
    private Button createShipmentScheduleBtn;
    @ViewComponent
    private Button removeShipmentScheduleBtn;
    @ViewComponent
    private Button createShipmentScheduleItemBtn;
    @ViewComponent
    private Button editShipmentScheduleItemBtn;
    @ViewComponent
    private Button removeShipmentScheduleItemBtn;

    @ViewComponent
    private Button createPaymentScheduleBtn;
    @ViewComponent
    private Button removePaymentScheduleBtn;
    @ViewComponent
    private Button createPaymentScheduleItemBtn;
    @ViewComponent
    private Button editPaymentScheduleItemBtn;
    @ViewComponent
    private Button removePaymentScheduleItemBtn;

    @ViewComponent
    private DataGrid<ShipmentScheduleItem> shipmentScheduleItemsDataGrid;
    @ViewComponent
    private DataGrid<PaymentScheduleItem> paymentScheduleItemsDataGrid;
    @ViewComponent
    private JmixTabSheet projectTabs;

    private Tab shipmentScheduleTab;
    private Tab paymentScheduleTab;
    private String shipmentScheduleTabBaseLabel;
    private String paymentScheduleTabBaseLabel;
    private boolean shipmentSortingInProgress;
    private boolean paymentSortingInProgress;
    @ViewComponent
    private MessageBundle messageBundle;

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        updateScheduleControls();
        sortShipmentItemsInMemory();
        sortPaymentItemsInMemory();
        updateTabLabels();
        updateGridTotals();
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        updateTabLabels();
        updateGridTotals();
    }

    @Subscribe("createShipmentScheduleBtn")
    public void onCreateShipmentScheduleBtnClick(final ClickEvent<Button> event) {
        Project project = getEditedEntity();
        if (project.getShipmentSchedule() != null) {
            return;
        }
        ShipmentSchedule shipmentSchedule = dataContext.create(ShipmentSchedule.class);
        shipmentSchedule.setProject(project);
        shipmentSchedule.setItems(new ArrayList<>());
        project.setShipmentSchedule(shipmentSchedule);
        updateScheduleControls();
        updateTabLabels();
        updateGridTotals();
    }

    @Subscribe("removeShipmentScheduleBtn")
    public void onRemoveShipmentScheduleBtnClick(final ClickEvent<Button> event) {
        Project project = getEditedEntity();
        ShipmentSchedule shipmentSchedule = project.getShipmentSchedule();
        if (shipmentSchedule == null) {
            return;
        }
        dataContext.remove(shipmentSchedule);
        project.setShipmentSchedule(null);
        updateScheduleControls();
        updateTabLabels();
        updateGridTotals();
    }

    @Subscribe("createPaymentScheduleBtn")
    public void onCreatePaymentScheduleBtnClick(final ClickEvent<Button> event) {
        Project project = getEditedEntity();
        if (project.getPaymentSchedule() != null) {
            return;
        }
        PaymentSchedule paymentSchedule = dataContext.create(PaymentSchedule.class);
        paymentSchedule.setProject(project);
        paymentSchedule.setItems(new ArrayList<>());
        project.setPaymentSchedule(paymentSchedule);
        updateScheduleControls();
        updateTabLabels();
        updateGridTotals();
    }

    @Subscribe("removePaymentScheduleBtn")
    public void onRemovePaymentScheduleBtnClick(final ClickEvent<Button> event) {
        Project project = getEditedEntity();
        PaymentSchedule paymentSchedule = project.getPaymentSchedule();
        if (paymentSchedule == null) {
            return;
        }
        dataContext.remove(paymentSchedule);
        project.setPaymentSchedule(null);
        updateScheduleControls();
        updateTabLabels();
        updateGridTotals();
    }

    private void updateScheduleControls() {
        Project project = getEditedEntity();

        boolean hasShipmentSchedule = project.getShipmentSchedule() != null;
        createShipmentScheduleBtn.setEnabled(!hasShipmentSchedule);
        removeShipmentScheduleBtn.setEnabled(hasShipmentSchedule);
        createShipmentScheduleItemBtn.setEnabled(hasShipmentSchedule);
        editShipmentScheduleItemBtn.setEnabled(hasShipmentSchedule);
        removeShipmentScheduleItemBtn.setEnabled(hasShipmentSchedule);
        shipmentScheduleItemsDataGrid.setEnabled(hasShipmentSchedule);

        boolean hasPaymentSchedule = project.getPaymentSchedule() != null;
        createPaymentScheduleBtn.setEnabled(!hasPaymentSchedule);
        removePaymentScheduleBtn.setEnabled(hasPaymentSchedule);
        createPaymentScheduleItemBtn.setEnabled(hasPaymentSchedule);
        editPaymentScheduleItemBtn.setEnabled(hasPaymentSchedule);
        removePaymentScheduleItemBtn.setEnabled(hasPaymentSchedule);
        paymentScheduleItemsDataGrid.setEnabled(hasPaymentSchedule);
    }

    @Subscribe(id = "shipmentScheduleItemsDc", target = Target.DATA_CONTAINER)
    public void onShipmentScheduleItemsDcCollectionChange(
            final CollectionContainer.CollectionChangeEvent<ShipmentScheduleItem> event) {
        updateTabLabels();
        updateGridTotals();
        if (event.getChangeType() != CollectionChangeType.ADD_ITEMS) {
            return;
        }
        sortShipmentItemsInMemory();
    }

    @Subscribe(id = "shipmentScheduleItemsDc", target = Target.DATA_CONTAINER)
    public void onShipmentScheduleItemsDcItemPropertyChange(
            final InstanceContainer.ItemPropertyChangeEvent<ShipmentScheduleItem> event) {
        if ("itemDate".equals(event.getProperty())) {
            sortShipmentItemsInMemory();
        }
        if ("amount".equals(event.getProperty())) {
            updateGridTotals();
        }
    }

    @Subscribe(id = "paymentScheduleItemsDc", target = Target.DATA_CONTAINER)
    public void onPaymentScheduleItemsDcCollectionChange(
            final CollectionContainer.CollectionChangeEvent<PaymentScheduleItem> event) {
        updateTabLabels();
        updateGridTotals();
        if (event.getChangeType() != CollectionChangeType.ADD_ITEMS) {
            return;
        }
        sortPaymentItemsInMemory();
    }

    @Subscribe(id = "paymentScheduleItemsDc", target = Target.DATA_CONTAINER)
    public void onPaymentScheduleItemsDcItemPropertyChange(
            final InstanceContainer.ItemPropertyChangeEvent<PaymentScheduleItem> event) {
        if ("itemDate".equals(event.getProperty())) {
            sortPaymentItemsInMemory();
        }
        if ("amount".equals(event.getProperty())) {
            updateGridTotals();
        }
    }

    private void sortShipmentItemsInMemory() {
        if (shipmentSortingInProgress) {
            return;
        }
        var sorter = shipmentScheduleItemsDc.getSorter();
        if (sorter == null) {
            return;
        }
        shipmentSortingInProgress = true;
        try {
            sorter.sort(Sort.by(Sort.Direction.ASC, "itemDate"));
        } finally {
            shipmentSortingInProgress = false;
        }
    }

    private void sortPaymentItemsInMemory() {
        if (paymentSortingInProgress) {
            return;
        }
        var sorter = paymentScheduleItemsDc.getSorter();
        if (sorter == null) {
            return;
        }
        paymentSortingInProgress = true;
        try {
            sorter.sort(Sort.by(Sort.Direction.ASC, "itemDate"));
        } finally {
            paymentSortingInProgress = false;
        }
    }

    private void initTabBaseLabels() {
        if (!resolveTabsIfNeeded()) {
            return;
        }
        if (shipmentScheduleTabBaseLabel == null) {
            shipmentScheduleTabBaseLabel = shipmentScheduleTab.getLabel();
        }
        if (paymentScheduleTabBaseLabel == null) {
            paymentScheduleTabBaseLabel = paymentScheduleTab.getLabel();
        }
    }

    private void updateTabLabels() {
        initTabBaseLabels();
        if (shipmentScheduleTab == null
                || paymentScheduleTab == null
                || shipmentScheduleTabBaseLabel == null
                || paymentScheduleTabBaseLabel == null) {
            return;
        }
        shipmentScheduleTab.setLabel(shipmentScheduleTabBaseLabel + " (" + shipmentScheduleItemsDc.getItems().size() + ")");
        paymentScheduleTab.setLabel(paymentScheduleTabBaseLabel + " (" + paymentScheduleItemsDc.getItems().size() + ")");
    }

    private void updateGridTotals() {
        setAmountFooter(shipmentScheduleItemsDataGrid, sumShipmentScheduleItems());
        setAmountFooter(paymentScheduleItemsDataGrid, sumPaymentScheduleItems());
    }

    private BigDecimal sumShipmentScheduleItems() {
        return shipmentScheduleItemsDc.getItems().stream()
                .map(ShipmentScheduleItem::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumPaymentScheduleItems() {
        return paymentScheduleItemsDc.getItems().stream()
                .map(PaymentScheduleItem::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void setAmountFooter(DataGrid<?> dataGrid, BigDecimal totalAmount) {
        Grid.Column<?> amountColumn = dataGrid.getColumnByKey("amount");
        if (amountColumn == null) {
            return;
        }
        amountColumn.setFooter(messageBundle.getMessage("projectDetailView.total")
                + " " + totalAmount.stripTrailingZeros().toPlainString());
    }

    private boolean resolveTabsIfNeeded() {
        if (shipmentScheduleTab == null) {
            shipmentScheduleTab = findTab("shipmentScheduleTab");
        }
        if (paymentScheduleTab == null) {
            paymentScheduleTab = findTab("paymentScheduleTab");
        }

        return shipmentScheduleTab != null
                && paymentScheduleTab != null;
    }

    private Tab findTab(String id) {
        return projectTabs.getChildren()
                .filter(component -> component instanceof Tab)
                .map(component -> (Tab) component)
                .filter(tab -> tab.getId().isPresent() && id.equals(tab.getId().get()))
                .findFirst()
                .orElse(null);
    }
}
