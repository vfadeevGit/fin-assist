package ru.stnovator.finassist.view.addendum;

import com.vaadin.flow.component.dependency.JsModule;
import io.jmix.flowui.component.tabsheet.JmixTabSheet;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.model.CollectionContainer;
import ru.stnovator.finassist.entity.ShipmentScheduleCorrection;
import ru.stnovator.finassist.entity.PaymentScheduleCorrection;
import ru.stnovator.finassist.entity.Customer;
import ru.stnovator.finassist.service.RelatedDataAccess;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.data.value.ContainerValueSource;
import io.jmix.flowui.component.validation.ValidationErrors;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import io.jmix.flowui.component.grid.DataGrid;
import jakarta.validation.Validator;
import io.jmix.core.entity.EntitySystemAccess;
import io.jmix.core.entity.EntityValues;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.view.ViewValidation;
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
@JsModule("./addendum/compact-date.js")
public class AddendumDetailView extends StandardDetailView<Addendum> {
    @Autowired
    private DetailViewPresentation detailViewPresentation;

    @Subscribe
    public void onPresentationReady(final ReadyEvent event) {
        detailViewPresentation.initialize(this);
        resizeComparisons();
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
    private TypedDatePicker<LocalDate> endDateBeforeField;
    @ViewComponent
    private Span termsHint;

    @ViewComponent private AddendumAmountField amountAdjustmentField;
    @ViewComponent private AddendumEndDateField newEndDateField;
    @ViewComponent private Span addendumIdentity;
    @ViewComponent private JmixTabSheet correctionsTabs;
    @ViewComponent private MessageBundle messageBundle;
    @ViewComponent private CollectionContainer<ShipmentScheduleCorrection> shipmentScheduleCorrectionsDc;
    @ViewComponent private CollectionContainer<PaymentScheduleCorrection> paymentScheduleCorrectionsDc;
    @Autowired private RelatedDataAccess relatedDataAccess;
    @Autowired private MetadataTools metadataTools;
    @Autowired private Validator validator;
    @Autowired private DialogWindows dialogWindows;
    @Autowired private ViewValidation viewValidation;
    @ViewComponent private DataGrid<ShipmentScheduleCorrection> shipmentScheduleCorrectionsDataGrid;
    @ViewComponent private DataGrid<PaymentScheduleCorrection> paymentScheduleCorrectionsDataGrid;

    @Autowired private ru.stnovator.finassist.service.CorrectionComparisonService comparisonService;
    private CorrectionGridPresentation<ShipmentScheduleCorrection> shipmentPresentation;
    private CorrectionGridPresentation<PaymentScheduleCorrection> paymentPresentation;
    private boolean comparisonRefreshQueued;
    private boolean comparisonReady;

    private UUID parentContractId;
    private String returnTo;
    private boolean contractContextApplied;

    @Subscribe
    public void onInit(final InitEvent event) {
        urlQueryParameters.registerBinder(new ParentContractQueryParametersBinder());
        amountAdjustmentField.setValueSource(new ContainerValueSource<>(
                getEditedEntityContainer(), "amountAdjustment"));
        amountAdjustmentField.setLabel(messageBundle.getMessage("adjustmentLabel"));
        amountAdjustmentField.setHelperText(messageBundle.getMessage("amountAdjustmentHelp"));
        amountAdjustmentField.setCommitListener(this::refreshTerms);
        amountAdjustmentField.addBlurListener(e -> refreshTerms());
        newEndDateField.setValueSource(new ContainerValueSource<>(getEditedEntityContainer(), "newEndDate"));
        newEndDateField.setLabel(messageBundle.getMessage("endDateLabel"));
        newEndDateField.setHelperText(messageBundle.getMessage("compactDateHelp"));
        newEndDateField.setAutoOpen(false);
        newEndDateField.setErrorMessage(messageBundle.getMessage("invalidEndDate"));
        newEndDateField.addBlurListener(e -> refreshTerms());
        newEndDateField.addInvalidChangeListener(e -> refreshTerms());
        newEndDateField.addAttachListener(e -> e.getUI().beforeClientResponse(newEndDateField,
                context -> newEndDateField.getElement().executeJs("window.finassistConfigureEndDate(this)")));
        shipmentPresentation = new CorrectionGridPresentation<>(shipmentScheduleCorrectionsDataGrid,
                shipmentScheduleCorrectionsDc, this::getEditedEntity, comparisonService,
                ru.stnovator.finassist.service.CorrectionComparisonService.Kind.SHIPMENT, relatedDataAccess, messageBundle);
        paymentPresentation = new CorrectionGridPresentation<>(paymentScheduleCorrectionsDataGrid,
                paymentScheduleCorrectionsDc, this::getEditedEntity, comparisonService,
                ru.stnovator.finassist.service.CorrectionComparisonService.Kind.PAYMENT, relatedDataAccess, messageBundle);
        dataContext.addChangeListener(e -> queueComparisonRefresh());
        correctionsTabs.addSelectedChangeListener(e -> {
            if (correctionsTabs.getSelectedIndex() == 1) detailViewPresentation.refreshGrid(shipmentScheduleCorrectionsDataGrid);
            if (correctionsTabs.getSelectedIndex() == 2) detailViewPresentation.refreshGrid(paymentScheduleCorrectionsDataGrid);
            resizeComparisons();
        });
        shipmentScheduleCorrectionsDc.addCollectionChangeListener(e -> { updateTabCaptions(); queueComparisonRefresh(); });
        paymentScheduleCorrectionsDc.addCollectionChangeListener(e -> { updateTabCaptions(); queueComparisonRefresh(); });
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
        updateTabCaptions();
        updateIdentity();
        refreshTerms();
        comparisonReady = true;
        refreshComparisons();
    }

    private void queueComparisonRefresh() {
        if (!comparisonReady || comparisonRefreshQueued) return;
        getUI().ifPresent(ui -> {
            comparisonRefreshQueued = true;
            ui.beforeClientResponse(this, context -> {
                comparisonRefreshQueued = false;
                refreshComparisons();
            });
        });
    }

    private void refreshComparisons() {
        shipmentPresentation.refresh();
        paymentPresentation.refresh();
    }

    private void resizeComparisons() {
        if (shipmentPresentation != null) shipmentPresentation.resize();
        if (paymentPresentation != null) paymentPresentation.resize();
    }

    @Subscribe(id = "addendumDc", target = Target.DATA_CONTAINER)
    public void onAddendumPropertyChange(InstanceContainer.ItemPropertyChangeEvent<Addendum> event) {
        updateIdentity();
        if (Set.of("contract", "effectiveDate", "amountAdjustment", "newEndDate").contains(event.getProperty())) {
            refreshTerms();
        }
    }

    private void refreshTerms() {
        amountAfterField.setHelperText("");
        endDateAfterField.setHelperText("");
        try {
            var preview = contractTermsService.preview(getEditedEntity());
            amountBeforeField.setTypedValue(preview.before().totalAmount());
            endDateBeforeField.setValue(preview.before().endDate());
            if (amountAdjustmentField.isInvalid() || newEndDateField.isInvalid()) {
                amountAfterField.clear();
                endDateAfterField.clear();
                termsHint.setText(messageBundle.getMessage("invalidInputHint"));
                termsHint.setVisible(true);
                return;
            }
            amountAfterField.setTypedValue(preview.after().totalAmount());
            endDateAfterField.setValue(preview.after().endDate());
            amountAfterField.setHelperText(getEditedEntity().getAmountAdjustment() == null
                    ? messageBundle.getMessage("termsUnchanged") : "");
            endDateAfterField.setHelperText(getEditedEntity().getNewEndDate() == null
                    ? messageBundle.getMessage("termsUnchanged") : "");
            termsHint.setVisible(false);
        } catch (ContractTermsException e) {
            amountBeforeField.clear();
            endDateBeforeField.clear();
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

    private void updateTabCaptions() {
        correctionsTabs.getTabAt(0).setLabel(messageBundle.getMessage("generalTab"));
        correctionsTabs.getTabAt(1).setLabel(messageBundle.formatMessage("shipmentTabCount", shipmentScheduleCorrectionsDc.getItems().size()));
        correctionsTabs.getTabAt(2).setLabel(messageBundle.formatMessage("paymentTabCount", paymentScheduleCorrectionsDc.getItems().size()));
    }

    private void updateIdentity() {
        Addendum item = getEditedEntity();
        List<String> parts = new ArrayList<>();
        if (relatedDataAccess.canRead(Addendum.class, "number")) {
            parts.add(item.getNumber() == null || item.getNumber().isBlank()
                    ? messageBundle.getMessage("newAddendum") : item.getNumber());
        }
        if (item.getContract() != null && relatedDataAccess.canRead(Addendum.class, "contract")
                && relatedDataAccess.canRead(Contract.class, "internalID", "startDate", "customer")
                && relatedDataAccess.canRead(Customer.class, "name")) {
            parts.add(metadataTools.getInstanceName(item.getContract()));
        }
        addendumIdentity.setText(String.join(" · ", parts));
    }

    @Override
    protected ValidationErrors validateView() {
        // Jmix disables inactive tab content: activate General before component validation.
        int previous = correctionsTabs.getSelectedIndex();
        correctionsTabs.setSelectedIndex(0);
        var errors = super.validateView();
        if (errors.isEmpty()) {
            updateTabCaptions();
            correctionsTabs.setSelectedIndex(previous);
            errors = validateCorrections();
        } else {
            correctionsTabs.getTabAt(0).setLabel(messageBundle.getMessage("generalTabInvalid"));
        }
        return errors;
    }

    private ValidationErrors validateCorrections() {
        for (ShipmentScheduleCorrection correction : shipmentScheduleCorrectionsDc.getItems()) {
            Object badItem = firstInvalidItem(correction.getItems());
            if (hasValidationErrors(correction) || badItem != null) {
                return revealCorrection(correction, shipmentScheduleCorrectionsDataGrid, 1,
                        hasValidationErrors(correction) ? null : badItem, "shipmentScheduleCorrectionItemsDataGrid");
            }
        }
        for (PaymentScheduleCorrection correction : paymentScheduleCorrectionsDc.getItems()) {
            Object badItem = firstInvalidItem(correction.getItems());
            if (hasValidationErrors(correction) || badItem != null) {
                return revealCorrection(correction, paymentScheduleCorrectionsDataGrid, 2,
                        hasValidationErrors(correction) ? null : badItem, "paymentScheduleCorrectionItemsDataGrid");
            }
        }
        return ValidationErrors.none();
    }

    private boolean hasValidationErrors(Object entity) {
        var erased = EntitySystemAccess.getSecurityState(entity).getErasedAttributes();
        return validator.validate(entity).stream()
                .anyMatch(violation -> !erased.contains(violation.getPropertyPath().toString()));
    }

    private Object firstInvalidItem(List<?> items) {
        return items == null ? null : items.stream().filter(this::hasValidationErrors).findFirst().orElse(null);
    }

    private <T> ValidationErrors revealCorrection(T correction, DataGrid<T> grid, int tabIndex,
                                                 Object badItem, String itemsGridId) {
        correctionsTabs.setSelectedIndex(tabIndex);
        grid.select(correction);
        var errors = new ValidationErrors();
        errors.add(grid, messageBundle.getMessage("invalidCorrection"));
        var edit = grid.getAction("edit");
        edit.refreshState();
        if (edit.isEnabled() && edit.isVisible()) {
            // Building from the secured composition grid preserves its parent DataContext.
            dialogWindows.detail(grid).withAfterOpenListener(event -> {
                if (badItem == null) {
                    revealFieldErrors(event.getView());
                } else {
                    DataGrid<Object> items = (DataGrid<Object>) UiComponentUtils.getComponent(event.getView(), itemsGridId);
                    items.getItems().getItems().stream()
                            .filter(item -> EntityValues.getId(item).equals(EntityValues.getId(badItem)))
                            .findFirst().ifPresent(items::select);
                    var itemEdit = items.getAction("edit");
                    itemEdit.refreshState();
                    if (itemEdit.isEnabled() && itemEdit.isVisible()) {
                        dialogWindows.detail(items).withAfterOpenListener(itemEvent ->
                                revealFieldErrors(itemEvent.getView())).open();
                    }
                }
            }).open();
        }
        return errors;
    }

    private void revealFieldErrors(Component view) {
        var errors = viewValidation.validateUiComponents(view);
        viewValidation.focusProblemComponent(errors);
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
                    .getOrDefault("parentContractId", List.of())
                    .stream()
                    .findFirst()
                    .map(UUID::fromString)
                    .orElse(null);
            returnTo = queryParameters.getParameters()
                    .getOrDefault("returnTo", List.of())
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
