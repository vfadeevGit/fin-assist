package ru.stnovator.finassist.view.addendum;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.Addendum;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "addenda", layout = MainView.class)
@ViewController("Addendum.list")
@ViewDescriptor("addendum-list-view.xml")
@LookupComponent("addendaDataGrid")
@DialogMode(width = "64em")
public class AddendumListView extends StandardListView<Addendum> {
}
