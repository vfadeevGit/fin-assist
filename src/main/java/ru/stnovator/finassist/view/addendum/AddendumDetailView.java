package ru.stnovator.finassist.view.addendum;

import ru.stnovator.finassist.view.support.DetailViewPresentation;
import io.jmix.flowui.view.DialogMode;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.datepicker.TypedDatePicker;
import io.jmix.flowui.model.InstanceContainer;
import io.jmix.flowui.view.Target;
import com.vaadin.flow.component.html.Span;
import ru.stnovator.finassist.service.ContractTermsService;
import ru.stnovator.finassist.service.ContractTermsException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
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

@DialogMode(width = "64rem", height = "min(52rem, calc(100dvh - 6rem))", maxWidth = "calc(100vw - 2rem)", keepInViewport = true, resizable = true)
@Route(value = "addenda/:id", layout = MainView.class)
@ViewController("Addendum.detail")
@ViewDescriptor("addendum-detail-view.xml")
@EditedEntityContainer("addendumDc")
public class AddendumDetailView extends StandardDetailView<Addendum> {
    @Autowired
    private DetailViewPresentation detailViewPresentation;

    @Subscribe
    public void onPresentationReady(final ReadyEvent event) {
        detailViewPresentation.initialize(this);
    }

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

    @Autowired
    private ContractTermsService contractTermsService;
    @Autowired
    private EntityStates entityStates;
    @ViewComponent
    private TypedTextField<BigDecimal> amountBeforeField;
    @ViewComponent
    private TypedTextField<BigDecimal> amountAfterField;
    @ViewComponent
    private TypedDatePicker<LocalDate> endDateAfterField;
    @ViewComponent
    private Span termsHint;

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
        if (!entityStates.isNew(getEditedEntity())) contractField.setReadOnly(true);
        refreshTerms();
    }

    @Subscribe(id = "addendumDc", target = Target.DATA_CONTAINER)
    public void onAddendumPropertyChange(InstanceContainer.ItemPropertyChangeEvent<Addendum> event) {
        if (Set.of("contract", "effectiveDate", "amountAdjustment", "newEndDate").contains(event.getProperty())) {
            refreshTerms();
        }
    }

    private void refreshTerms() {
        try {
            var preview = contractTermsService.preview(getEditedEntity());
            amountBeforeField.setTypedValue(preview.before().totalAmount());
            amountAfterField.setTypedValue(preview.after().totalAmount());
            endDateAfterField.setValue(preview.after().endDate());
            termsHint.setVisible(false);
        } catch (ContractTermsException e) {
            amountBeforeField.clear();
            amountAfterField.clear();
            endDateAfterField.clear();
            termsHint.setText(e.getMessage());
            termsHint.setVisible(true);
        }
    }

    @Subscribe
    public void onValidation(ValidationEvent event) {
        if (getEditedEntity().getContract() == null || getEditedEntity().getEffectiveDate() == null) return;
        try {
            contractTermsService.preview(getEditedEntity());
        } catch (ContractTermsException e) {
            event.getErrors().add(e.getMessage());
        }
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
