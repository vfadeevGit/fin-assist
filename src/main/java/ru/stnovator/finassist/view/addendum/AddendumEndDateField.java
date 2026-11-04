package ru.stnovator.finassist.view.addendum;

import io.jmix.flowui.component.datepicker.TypedDatePicker;
import java.time.LocalDate;

/** Retains malformed client text instead of letting a null binding echo erase it. */
public class AddendumEndDateField extends TypedDatePicker<LocalDate> {
    @Override
    protected void setValueInternal(LocalDate value) {
        if (value == null && isInputUnparsable()) return;
        super.setValueInternal(value);
    }

    @Override
    protected void validate() {
        if (fieldDelegate != null) {
            fieldDelegate.setConversionInvalid(isInputUnparsable());
            super.validate();
        }
    }
}
