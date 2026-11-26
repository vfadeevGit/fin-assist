package ru.stnovator.finassist.view.support;

import com.vaadin.flow.component.datepicker.DatePicker;

/** Shared calendar-date presentation; validation and model binding remain owned by Jmix. */
public final class ApplicationDatePickerConfigurer {
    private ApplicationDatePickerConfigurer() {
    }

    public static void configure(DatePicker picker) {
        if (picker.getElement().hasAttribute("data-fin-date-input")) {
            return;
        }
        picker.getElement().setAttribute("data-fin-date-input", "true");
        picker.setI18n(picker.getI18n().setDateFormats("dd.MM.yyyy", "ddMMyyyy", "ddMMyy", "dd.MM.yy"));
        picker.addAttachListener(event -> event.getUI().beforeClientResponse(picker,
                context -> picker.getElement().executeJs("window.finassistConfigureDateInput(this)")));
    }
}
