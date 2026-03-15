package ru.stnovator.finassist.view.paymentschedulecorrection;

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
import ru.stnovator.finassist.entity.PaymentScheduleCorrection;
import ru.stnovator.finassist.entity.PaymentScheduleCorrectionItem;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "payment-schedule-corrections/:id", layout = MainView.class)
@ViewController("PaymentScheduleCorrection.detail")
@ViewDescriptor("payment-schedule-correction-detail-view.xml")
@EditedEntityContainer("paymentScheduleCorrectionDc")
public class PaymentScheduleCorrectionDetailView extends StandardDetailView<PaymentScheduleCorrection> {
    @ViewComponent
    private CollectionContainer<PaymentScheduleCorrectionItem> paymentScheduleCorrectionItemsDc;

    private boolean sortingInProgress;

    @Subscribe(id = "paymentScheduleCorrectionItemsDc", target = Target.DATA_CONTAINER)
    public void onPaymentScheduleCorrectionItemsDcCollectionChange(
            final CollectionContainer.CollectionChangeEvent<PaymentScheduleCorrectionItem> event) {
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
}
