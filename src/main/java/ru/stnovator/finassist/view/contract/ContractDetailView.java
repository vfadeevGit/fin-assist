package ru.stnovator.finassist.view.contract;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.component.datepicker.TypedDatePicker;
import io.jmix.flowui.view.*;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.view.main.MainView;

import java.time.LocalDate;

@Route(value = "contracts/:id", layout = MainView.class)
@ViewController("Contract.detail")
@ViewDescriptor("contract-detail-view.xml")
@EditedEntityContainer("contractDc")
public class ContractDetailView extends StandardDetailView<Contract> {
    @Subscribe("internalDateBeginField")
    public void onInternalDateBeginFieldComponentValueChange(final AbstractField.ComponentValueChangeEvent<TypedDatePicker<LocalDate>, LocalDate> event) {
        //TODO: Automatically recalculate contract longitude value
    }

    @Subscribe("internalDateEndField")
    public void onInternalDateEndFieldComponentValueChange(final AbstractField.ComponentValueChangeEvent<TypedDatePicker<LocalDate>, LocalDate> event) {
        //TODO: Automatically recalculate contract longitude value
    }


}