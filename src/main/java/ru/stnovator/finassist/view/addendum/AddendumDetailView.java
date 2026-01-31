package ru.stnovator.finassist.view.addendum;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.Addendum;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "addenda/:id", layout = MainView.class)
@ViewController("Addendum.detail")
@ViewDescriptor("addendum-detail-view.xml")
@EditedEntityContainer("addendumDc")
public class AddendumDetailView extends StandardDetailView<Addendum> {
}
