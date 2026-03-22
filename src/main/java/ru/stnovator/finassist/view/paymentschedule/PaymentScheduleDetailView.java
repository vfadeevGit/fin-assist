package ru.stnovator.finassist.view.paymentschedule;

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
import ru.stnovator.finassist.entity.PaymentSchedule;
import ru.stnovator.finassist.entity.PaymentScheduleItem;
import ru.stnovator.finassist.view.main.MainView;

import java.math.BigDecimal;

@Route(value = "payment-schedules/:id", layout = MainView.class)
@ViewController("PaymentSchedule.detail")
@ViewDescriptor("payment-schedule-detail-view.xml")
@EditedEntityContainer("paymentScheduleDc")
public class PaymentScheduleDetailView extends StandardDetailView<PaymentSchedule> {
    @ViewComponent
    private CollectionContainer<PaymentScheduleItem> paymentScheduleItemsDc;
    @ViewComponent
    private DataGrid<PaymentScheduleItem> paymentScheduleItemsDataGrid;
    @ViewComponent
    private MessageBundle messageBundle;

    private final DatatypeFormatter datatypeFormatter;

    private boolean sortingInProgress;

    public PaymentScheduleDetailView(DatatypeFormatter datatypeFormatter) {
        this.datatypeFormatter = datatypeFormatter;
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        updateGridTotal();
    }

    @Subscribe(id = "paymentScheduleItemsDc", target = Target.DATA_CONTAINER)
    public void onPaymentScheduleItemsDcCollectionChange(
            final CollectionContainer.CollectionChangeEvent<PaymentScheduleItem> event) {
        updateGridTotal();
        if (event.getChangeType() != CollectionChangeType.ADD_ITEMS) {
            return;
        }
        sortItemsInMemory();
    }

    @Subscribe(id = "paymentScheduleItemsDc", target = Target.DATA_CONTAINER)
    public void onPaymentScheduleItemsDcItemPropertyChange(
            final InstanceContainer.ItemPropertyChangeEvent<PaymentScheduleItem> event) {
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
        var sorter = paymentScheduleItemsDc.getSorter();
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
        Grid.Column<PaymentScheduleItem> amountColumn = paymentScheduleItemsDataGrid.getColumnByKey("amount");
        if (amountColumn == null) {
            return;
        }
        amountColumn.setFooter(messageBundle.getMessage("PaymentScheduleDetailView.total")
                + " " + datatypeFormatter.formatBigDecimal(sumItemsAmount()));
    }

    private BigDecimal sumItemsAmount() {
        return paymentScheduleItemsDc.getItems().stream()
                .map(PaymentScheduleItem::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
