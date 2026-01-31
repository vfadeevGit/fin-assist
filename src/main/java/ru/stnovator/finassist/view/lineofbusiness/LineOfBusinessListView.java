package ru.stnovator.finassist.view.lineofbusiness;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.LineOfBusiness;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "line-of-business", layout = MainView.class)
@ViewController("LineOfBusiness.list")
@ViewDescriptor("line-of-business-list-view.xml")
@LookupComponent("lineOfBusinessesDataGrid")
@DialogMode(width = "64em")
public class LineOfBusinessListView extends StandardListView<LineOfBusiness> {
}
