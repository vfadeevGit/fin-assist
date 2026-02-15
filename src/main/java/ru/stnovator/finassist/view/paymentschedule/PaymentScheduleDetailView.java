package ru.stnovator.finassist.view.paymentschedule;

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
import ru.stnovator.finassist.entity.PaymentSchedule;
import ru.stnovator.finassist.entity.PaymentScheduleItem;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "payment-schedules/:id", layout = MainView.class)
@ViewController("PaymentSchedule.detail")
@ViewDescriptor("payment-schedule-detail-view.xml")
@EditedEntityContainer("paymentScheduleDc")
public class PaymentScheduleDetailView extends StandardDetailView<PaymentSchedule> {
    @ViewComponent
    private CollectionContainer<PaymentScheduleItem> paymentScheduleItemsDc;

    private boolean sortingInProgress;

    @Subscribe(id = "paymentScheduleItemsDc", target = Target.DATA_CONTAINER)
    public void onPaymentScheduleItemsDcCollectionChange(
            final CollectionContainer.CollectionChangeEvent<PaymentScheduleItem> event) {
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
    }

    private void sortItemsInMemory() {
        if (sortingInProgress) {
            return;
        }
        sortingInProgress = true;
        try {
            paymentScheduleItemsDc.getSorter().sort(Sort.by(Sort.Direction.ASC, "itemDate"));
        } finally {
            sortingInProgress = false;
        }
    }
}
