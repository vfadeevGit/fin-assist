package ru.stnovator.finassist.view.lineofbusiness;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.*;
import ru.stnovator.finassist.entity.LineOfBusiness;
import ru.stnovator.finassist.view.main.MainView;


@Route(value = "lineOfBusinesses", layout = MainView.class)
@ViewController("LineOfBusiness.list")
@ViewDescriptor("line-of-business-list-view.xml")
@LookupComponent("lineOfBusinessesDataGrid")
@DialogMode(width = "64em")
public class LineOfBusinessListView extends StandardListView<LineOfBusiness> {
}