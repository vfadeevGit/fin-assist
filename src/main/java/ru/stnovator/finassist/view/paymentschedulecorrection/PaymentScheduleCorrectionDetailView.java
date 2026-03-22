package ru.stnovator.finassist.view.paymentschedulecorrection;

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
import ru.stnovator.finassist.entity.PaymentScheduleCorrection;
import ru.stnovator.finassist.entity.PaymentScheduleCorrectionItem;
import ru.stnovator.finassist.entity.PaymentScheduleItem;
import ru.stnovator.finassist.entity.Project;
import ru.stnovator.finassist.view.main.MainView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

@Route(value = "payment-schedule-corrections/:id", layout = MainView.class)
@ViewController("PaymentScheduleCorrection.detail")
@ViewDescriptor("payment-schedule-correction-detail-view.xml")
@EditedEntityContainer("paymentScheduleCorrectionDc")
public class PaymentScheduleCorrectionDetailView extends StandardDetailView<PaymentScheduleCorrection> {
    @ViewComponent
    private CollectionContainer<PaymentScheduleCorrectionItem> paymentScheduleCorrectionItemsDc;
    @ViewComponent
    private CollectionLoader<Project> projectsDl;
    @ViewComponent
    private H4 itemsTitle;
    @ViewComponent
    private MessageBundle messageBundle;
    @ViewComponent
    private DataGrid<PaymentScheduleCorrectionItem> paymentScheduleCorrectionItemsDataGrid;
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

    public PaymentScheduleCorrectionDetailView(DatatypeFormatter datatypeFormatter) {
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
            notifications.create(messageBundle.getMessage("PaymentScheduleCorrectionDetailView.projectRequiredForFill"))
                    .withType(Notifications.Type.WARNING)
                    .show();
            return;
        }

        Addendum addendum = getEditedEntity().getAddendum();
        LocalDate effectiveDate = addendum != null ? addendum.getEffectiveDate() : null;
        Set<LocalDate> existingItemDates = new HashSet<>();
        for (PaymentScheduleCorrectionItem correctionItem : paymentScheduleCorrectionItemsDc.getItems()) {
            if (correctionItem.getItemDate() != null) {
                existingItemDates.add(correctionItem.getItemDate());
            }
        }

        for (PaymentScheduleItem scheduleItem : loadPaymentScheduleItems(project, effectiveDate)) {
            if (!existingItemDates.add(scheduleItem.getItemDate())) {
                continue;
            }

            PaymentScheduleCorrectionItem correctionItem = dataContext.create(PaymentScheduleCorrectionItem.class);
            correctionItem.setCorrection(getEditedEntity());
            correctionItem.setItemDate(scheduleItem.getItemDate());
            correctionItem.setAmount(scheduleItem.getAmount());
            paymentScheduleCorrectionItemsDc.getMutableItems().add(correctionItem);
        }

        sortItemsInMemory();
    }

    @Subscribe("clearItemsBtn")
    public void onClearItemsBtnClick(final ClickEvent<Button> event) {
        dialogs.createOptionDialog()
                .withHeader(messageBundle.getMessage("PaymentScheduleCorrectionDetailView.clearItemsDialogTitle"))
                .withText(messageBundle.getMessage("PaymentScheduleCorrectionDetailView.clearItemsDialogText"))
                .withActions(
                        new DialogAction(DialogAction.Type.YES)
                                .withText(messageBundle.getMessage("PaymentScheduleCorrectionDetailView.clearItemsDialogConfirm"))
                                .withHandler(actionEvent -> clearAllItems()),
                        new DialogAction(DialogAction.Type.CANCEL)
                                .withText(messageBundle.getMessage("PaymentScheduleCorrectionDetailView.clearItemsDialogCancel"))
                )
                .open();
    }

    @Subscribe(id = "paymentScheduleCorrectionItemsDc", target = Target.DATA_CONTAINER)
    public void onPaymentScheduleCorrectionItemsDcCollectionChange(
            final CollectionContainer.CollectionChangeEvent<PaymentScheduleCorrectionItem> event) {
        updateGridTotal();
        if (event.getChangeType() != CollectionChangeType.ADD_ITEMS) {
            return;
        }
        sortItemsInMemory();
    }

    @Subscribe(id = "paymentScheduleCorrectionItemsDc", target = Target.DATA_CONTAINER)
    public void onPaymentScheduleCorrectionItemsDcItemPropertyChange(
            final InstanceContainer.ItemPropertyChangeEvent<PaymentScheduleCorrectionItem> event) {
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
        var sorter = paymentScheduleCorrectionItemsDc.getSorter();
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

    private java.util.List<PaymentScheduleItem> loadPaymentScheduleItems(Project project, LocalDate effectiveDate) {
        return dataManager.load(PaymentScheduleItem.class)
                .query("""
                        select e from PaymentScheduleItem e
                        where e.schedule.project = :project
                        order by e.itemDate asc
                        """)
                .parameter("project", project)
                .list();
    }

    private void clearAllItems() {
        for (PaymentScheduleCorrectionItem item : new ArrayList<>(paymentScheduleCorrectionItemsDc.getItems())) {
            paymentScheduleCorrectionItemsDc.getMutableItems().remove(item);
            dataContext.remove(item);
        }
        updateGridTotal();
    }

    private void updateItemsTitle() {
        Addendum addendum = getEditedEntity().getAddendum();
        if (addendum == null || addendum.getEffectiveDate() == null) {
            itemsTitle.setText(messageBundle.getMessage("PaymentScheduleCorrectionDetailView.items"));
            return;
        }

        itemsTitle.setText(messageBundle.formatMessage(
                "PaymentScheduleCorrectionDetailView.itemsWithEffectiveDate",
                datatypeFormatter.formatLocalDate(addendum.getEffectiveDate())));
    }

    private void updateGridTotal() {
        Grid.Column<PaymentScheduleCorrectionItem> amountColumn =
                paymentScheduleCorrectionItemsDataGrid.getColumnByKey("amount");
        if (amountColumn == null) {
            return;
        }
        amountColumn.setFooter(messageBundle.getMessage("PaymentScheduleCorrectionDetailView.total")
                + " " + datatypeFormatter.formatBigDecimal(sumItemsAmount()));
    }

    private BigDecimal sumItemsAmount() {
        return paymentScheduleCorrectionItemsDc.getItems().stream()
                .map(PaymentScheduleCorrectionItem::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
