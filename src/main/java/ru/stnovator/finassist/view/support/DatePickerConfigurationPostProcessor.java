package ru.stnovator.finassist.view.support;

import io.jmix.flowui.component.datepicker.TypedDatePicker;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/** Also covers pickers created later by PropertyFilter and custom TypedDatePicker subclasses. */
@Component
public class DatePickerConfigurationPostProcessor implements BeanPostProcessor {
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof TypedDatePicker<?> picker) {
            ApplicationDatePickerConfigurer.configure(picker);
        }
        return bean;
    }
}
