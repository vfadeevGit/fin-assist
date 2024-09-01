package ru.stnovator.finassist.view.worksheet;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.WorkSheet;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "workSheets/:id", layout = MainView.class)
@ViewController("WorkSheet.detail")
@ViewDescriptor("work-sheet-detail-view.xml")
@EditedEntityContainer("workSheetDc")
public class WorkSheetDetailView extends StandardDetailView<WorkSheet> {
}