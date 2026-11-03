package ru.stnovator.finassist.view.support;

import io.jmix.flowui.Notifications;
import io.jmix.flowui.exception.AbstractUiExceptionHandler;
import org.springframework.stereotype.Component;
import ru.stnovator.finassist.service.ContractTermsException;

@Component
public class ContractTermsExceptionHandler extends AbstractUiExceptionHandler {
    private final Notifications notifications;

    public ContractTermsExceptionHandler(Notifications notifications) {
        super(ContractTermsException.class.getName());
        this.notifications = notifications;
    }

    @Override
    protected void doHandle(String className, String message, Throwable throwable) {
        notifications.create(message).withType(Notifications.Type.WARNING).show();
    }
}
