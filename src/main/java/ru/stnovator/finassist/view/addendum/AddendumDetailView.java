package ru.stnovator.finassist.view.addendum;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import io.jmix.core.DataManager;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.valuepicker.EntityPicker;
import io.jmix.flowui.facet.UrlQueryParametersFacet;
import io.jmix.flowui.facet.urlqueryparameters.AbstractUrlQueryParametersBinder;
import io.jmix.flowui.model.DataContext;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.StandardOutcome;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import io.jmix.flowui.view.ViewComponent;
import org.springframework.beans.factory.annotation.Autowired;
import ru.stnovator.finassist.entity.Addendum;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "addenda/:id", layout = MainView.class)
@ViewController("Addendum.detail")
@ViewDescriptor("addendum-detail-view.xml")
@EditedEntityContainer("addendumDc")
public class AddendumDetailView extends StandardDetailView<Addendum> {
    @ViewComponent
    private EntityPicker<Contract> contractField;
    @ViewComponent
    private DataContext dataContext;
    @ViewComponent
    private UrlQueryParametersFacet urlQueryParameters;

    @Autowired
    private DataManager dataManager;
    @Autowired
    private ViewNavigators viewNavigators;

    private java.util.UUID parentContractId;
    private String returnTo;
    private boolean contractContextApplied;

    @Subscribe
    public void onInit(final InitEvent event) {
        urlQueryParameters.registerBinder(new ParentContractQueryParametersBinder());
    }

    @Subscribe
    public void onInitEntity(final InitEntityEvent<Addendum> event) {
        if (parentContractId == null || event.getEntity().getContract() != null) {
            return;
        }

        Contract contract = dataManager.load(Contract.class).id(parentContractId).one();
        event.getEntity().setContract(dataContext.merge(contract));
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        applyContractContextIfNeeded();
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        applyContractContextIfNeeded();
    }

    private void applyContractContextIfNeeded() {
        if (parentContractId == null) {
            return;
        }

        if (!contractContextApplied && getEditedEntity().getContract() == null) {
            Contract contract = dataManager.load(Contract.class).id(parentContractId).one();
            getEditedEntity().setContract(dataContext.merge(contract));
        }
        contractField.setReadOnly(true);
        contractContextApplied = true;
    }

    @Subscribe
    public void onBeforeClose(final BeforeCloseEvent event) {
        if (parentContractId == null || !"addendumList".equals(returnTo)) {
            return;
        }
        if (!event.closedWith(StandardOutcome.SAVE) && !event.closedWith(StandardOutcome.CLOSE)) {
            return;
        }

        event.preventClose();
        viewNavigators.listView(this, Addendum.class)
                .withViewClass(AddendumListView.class)
                .navigate();
    }

    private class ParentContractQueryParametersBinder extends AbstractUrlQueryParametersBinder {
        @Override
        public void updateState(QueryParameters queryParameters) {
            parentContractId = queryParameters.getParameters()
                    .getOrDefault("parentContractId", java.util.List.of())
                    .stream()
                    .findFirst()
                    .map(java.util.UUID::fromString)
                    .orElse(null);
            returnTo = queryParameters.getParameters()
                    .getOrDefault("returnTo", java.util.List.of())
                    .stream()
                    .findFirst()
                    .orElse(null);
            contractContextApplied = false;
        }

        @Override
        public Component getComponent() {
            return null;
        }
    }
}
