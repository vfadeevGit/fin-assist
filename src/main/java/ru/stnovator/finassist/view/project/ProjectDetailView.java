package ru.stnovator.finassist.view.project;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.valuepicker.EntityPicker;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.entity.Project;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "projects/:id", layout = MainView.class)
@ViewController("Project.detail")
@ViewDescriptor("project-detail-view.xml")
@EditedEntityContainer("projectDc")
public class ProjectDetailView extends StandardDetailView<Project> {
    @ViewComponent
    private EntityPicker<Contract> contractField;
    @Autowired
    private Notifications notifications;

    @Subscribe
    public void onBeforeSave(final BeforeSaveEvent event) {
        if (contractField.isEmpty()) {
            notifications.create("Ошибка", "Не указан контракт с клиентом! Изменения не будут сохранены")
                    .withType( Notifications.Type.ERROR)
                    .show();
            event.preventSave();
        }
    }
}