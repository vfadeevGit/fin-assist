package ru.stnovator.finassist.view.main;

import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.router.Route;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.app.main.StandardMainView;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

@Route("")
@ViewController("MainView")
@ViewDescriptor("main-view.xml")
public class MainView extends StandardMainView {

    @Autowired
    private CurrentAuthentication currentAuthentication;
    @Autowired
    private Notifications notifications;
    @ViewComponent
    private MessageBundle messageBundle;

    private boolean welcomeShown;

    @Subscribe
    public void onReady(final ReadyEvent event) {
        if (welcomeShown) {
            return;
        }

        notifications.create(messageBundle.formatMessage("welcomeNotification",
                        currentAuthentication.getUser().getUsername()))
                .withType(Notifications.Type.DEFAULT)
                .withPosition(Notification.Position.TOP_CENTER)
                .withDuration(5000)
                .show();
        welcomeShown = true;
    }
}
