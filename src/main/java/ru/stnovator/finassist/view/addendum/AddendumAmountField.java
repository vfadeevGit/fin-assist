package ru.stnovator.finassist.view.addendum;

import io.jmix.core.Messages;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.data.ConversionException;
import com.vaadin.flow.data.value.ValueChangeMode;
import java.math.BigDecimal;

/** Local conversion policy; retains the entity value source and its security/validation metadata. */
public class AddendumAmountField extends TypedTextField<BigDecimal> {
    private Runnable commitListener = () -> {};

    public void setCommitListener(Runnable listener) {
        commitListener = listener;
    }

    @Override
    protected void onValueChange(com.vaadin.flow.component.AbstractField.ComponentValueChangeEvent<TypedTextField<BigDecimal>, String> event) {
        boolean clientCommit = event.isFromClient() && !isVaadinValueChangeEnabled;
        super.onValueChange(event);
        if (clientCommit) commitListener.run();
    }

    @Override
    protected void initComponent() {
        super.initComponent();
        setValueChangeMode(ValueChangeMode.ON_CHANGE);
        setTrimEnabled(false);
    }

    static BigDecimal parse(String input) {
        if (input == null || input.replaceAll("[\\s\\u00a0\\u202f]", "").isEmpty()) return null;
        String text = input.replaceAll("^[\\s\\u00a0\\u202f]+|[\\s\\u00a0\\u202f]+$", "");
        if (!text.matches("-?(?:[0-9]+|[0-9]{1,3}(?:[ \\u00a0\\u202f][0-9]{3})+)(?:[.,][0-9]{1,2})?")) {
            throw new IllegalArgumentException("Invalid decimal");
        }
        BigDecimal value = new BigDecimal(text.replaceAll("[ \\u00a0\\u202f]", "").replace(',', '.'));
        if (value.abs().compareTo(BigDecimal.TEN.pow(17)) >= 0) throw new IllegalArgumentException("Decimal overflow");
        return value;
    }

    @Override
    protected BigDecimal convertToModel(String value) throws ConversionException {
        try {
            return parse(value);
        } catch (IllegalArgumentException ex) {
            throw new ConversionException(applicationContext.getBean(Messages.class)
                    .getMessage("ru.stnovator.finassist.view.addendum", "invalidAdjustment"));
        }
    }
}
