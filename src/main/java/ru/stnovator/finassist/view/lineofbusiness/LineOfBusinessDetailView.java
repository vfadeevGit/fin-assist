package ru.stnovator.finassist.view.lineofbusiness;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.LineOfBusiness;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "line-of-business/:id", layout = MainView.class)
@ViewController("LineOfBusiness.detail")
@ViewDescriptor("line-of-business-detail-view.xml")
@EditedEntityContainer("lineOfBusinessDc")
public class LineOfBusinessDetailView extends StandardDetailView<LineOfBusiness> {
}
