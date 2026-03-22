package ru.stnovator.finassist.view.shipmentschedulecorrection;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.router.Route;
import io.jmix.core.DataManager;
import io.jmix.core.Sort;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionChangeType;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.model.DataContext;
import io.jmix.flowui.model.InstanceContainer;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import io.jmix.flowui.view.ViewComponent;
import org.springframework.beans.factory.annotation.Autowired;
import ru.stnovator.finassist.entity.Addendum;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.entity.Project;
import ru.stnovator.finassist.entity.ShipmentScheduleCorrection;
import ru.stnovator.finassist.entity.ShipmentScheduleCorrectionItem;
import ru.stnovator.finassist.entity.ShipmentScheduleItem;
import ru.stnovator.finassist.view.main.MainView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

@Route(value = "shipment-schedule-corrections/:id", layout = MainView.class)
@ViewController("ShipmentScheduleCorrection.detail")
@ViewDescriptor("shipment-schedule-correction-detail-view.xml")
@EditedEntityContainer("shipmentScheduleCorrectionDc")
public class ShipmentScheduleCorrectionDetailView extends StandardDetailView<ShipmentScheduleCorrection> {
    @ViewComponent
    private CollectionContainer<ShipmentScheduleCorrectionItem> shipmentScheduleCorrectionItemsDc;
    @ViewComponent
    private CollectionLoader<Project> projectsDl;
    @ViewComponent
    private H4 itemsTitle;
    @ViewComponent
    private MessageBundle messageBundle;
    @ViewComponent
    private DataGrid<ShipmentScheduleCorrectionItem> shipmentScheduleCorrectionItemsDataGrid;
    @ViewComponent
    private DataContext dataContext;

    @Autowired
    private DataManager dataManager;
    @Autowired
    private Notifications notifications;
    @Autowired
    private Dialogs dialogs;

    private final DatatypeFormatter datatypeFormatter;

    private boolean sortingInProgress;

    public ShipmentScheduleCorrectionDetailView(DatatypeFormatter datatypeFormatter) {
        this.datatypeFormatter = datatypeFormatter;
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        refreshAvailableProjects();
        updateItemsTitle();
        updateGridTotal();
    }

    @Subscribe("addendumField")
    public void onAddendumFieldComponentValueChange(
            final com.vaadin.flow.component.AbstractField.ComponentValueChangeEvent<?, ?> event) {
        refreshAvailableProjects();
        updateItemsTitle();
    }

    @Subscribe("fillItemsFromScheduleBtn")
    public void onFillItemsFromScheduleBtnClick(final ClickEvent<Button> event) {
        Project project = getEditedEntity().getProject();
        if (project == null) {
            notifications.create(messageBundle.getMessage("ShipmentScheduleCorrectionDetailView.projectRequiredForFill"))
                    .withType(Notifications.Type.WARNING)
                    .show();
            return;
        }

        Addendum addendum = getEditedEntity().getAddendum();
        LocalDate effectiveDate = addendum != null ? addendum.getEffectiveDate() : null;
        Set<LocalDate> existingItemDates = new HashSet<>();
        for (ShipmentScheduleCorrectionItem correctionItem : shipmentScheduleCorrectionItemsDc.getItems()) {
            if (correctionItem.getItemDate() != null) {
                existingItemDates.add(correctionItem.getItemDate());
            }
        }

        for (ShipmentScheduleItem scheduleItem : loadShipmentScheduleItems(project, effectiveDate)) {
            if (!existingItemDates.add(scheduleItem.getItemDate())) {
                continue;
            }

            ShipmentScheduleCorrectionItem correctionItem = dataContext.create(ShipmentScheduleCorrectionItem.class);
            correctionItem.setCorrection(getEditedEntity());
            correctionItem.setItemDate(scheduleItem.getItemDate());
            correctionItem.setAmount(scheduleItem.getAmount());
            shipmentScheduleCorrectionItemsDc.getMutableItems().add(correctionItem);
        }

        sortItemsInMemory();
    }

    @Subscribe("clearItemsBtn")
    public void onClearItemsBtnClick(final ClickEvent<Button> event) {
        dialogs.createOptionDialog()
                .withHeader(messageBundle.getMessage("ShipmentScheduleCorrectionDetailView.clearItemsDialogTitle"))
                .withText(messageBundle.getMessage("ShipmentScheduleCorrectionDetailView.clearItemsDialogText"))
                .withActions(
                        new DialogAction(DialogAction.Type.YES)
                                .withText(messageBundle.getMessage("ShipmentScheduleCorrectionDetailView.clearItemsDialogConfirm"))
                                .withHandler(actionEvent -> clearAllItems()),
                        new DialogAction(DialogAction.Type.CANCEL)
                                .withText(messageBundle.getMessage("ShipmentScheduleCorrectionDetailView.clearItemsDialogCancel"))
                )
                .open();
    }

    @Subscribe(id = "shipmentScheduleCorrectionItemsDc", target = Target.DATA_CONTAINER)
    public void onShipmentScheduleCorrectionItemsDcCollectionChange(
            final CollectionContainer.CollectionChangeEvent<ShipmentScheduleCorrectionItem> event) {
        updateGridTotal();
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
        if ("amount".equals(event.getProperty())) {
            updateGridTotal();
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

    private void refreshAvailableProjects() {
        Contract contract = extractSelectedContract();
        if (contract == null) {
            projectsDl.getContainer().setItems(java.util.Collections.emptyList());
            getEditedEntity().setProject(null);
            return;
        }

        projectsDl.setParameter("contract", contract);
        projectsDl.load();

        Project selectedProject = getEditedEntity().getProject();
        if (selectedProject != null && !contract.equals(selectedProject.getContract())) {
            getEditedEntity().setProject(null);
        }
    }

    private Contract extractSelectedContract() {
        Addendum addendum = getEditedEntity().getAddendum();
        return addendum != null ? addendum.getContract() : null;
    }

    private java.util.List<ShipmentScheduleItem> loadShipmentScheduleItems(Project project, LocalDate effectiveDate) {
        return dataManager.load(ShipmentScheduleItem.class)
                .query("""
                        select e from ShipmentScheduleItem e
                        where e.schedule.project = :project
                        order by e.itemDate asc
                        """)
                .parameter("project", project)
                .list();
    }

    private void clearAllItems() {
        for (ShipmentScheduleCorrectionItem item : new ArrayList<>(shipmentScheduleCorrectionItemsDc.getItems())) {
            shipmentScheduleCorrectionItemsDc.getMutableItems().remove(item);
            dataContext.remove(item);
        }
        updateGridTotal();
    }

    private void updateItemsTitle() {
        Addendum addendum = getEditedEntity().getAddendum();
        if (addendum == null || addendum.getEffectiveDate() == null) {
            itemsTitle.setText(messageBundle.getMessage("ShipmentScheduleCorrectionDetailView.items"));
            return;
        }

        itemsTitle.setText(messageBundle.formatMessage(
                "ShipmentScheduleCorrectionDetailView.itemsWithEffectiveDate",
                datatypeFormatter.formatLocalDate(addendum.getEffectiveDate())));
    }

    private void updateGridTotal() {
        Grid.Column<ShipmentScheduleCorrectionItem> amountColumn =
                shipmentScheduleCorrectionItemsDataGrid.getColumnByKey("amount");
        if (amountColumn == null) {
            return;
        }
        amountColumn.setFooter(messageBundle.getMessage("ShipmentScheduleCorrectionDetailView.total")
                + " " + datatypeFormatter.formatBigDecimal(sumItemsAmount()));
    }

    private BigDecimal sumItemsAmount() {
        return shipmentScheduleCorrectionItemsDc.getItems().stream()
                .map(ShipmentScheduleCorrectionItem::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
