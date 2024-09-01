package ru.stnovator.finassist.view.worksheetdetail;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import ru.stnovator.finassist.entity.WorkSheetDetail;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "workSheetDetails/:id", layout = MainView.class)
@ViewController("WorkSheetDetail.detail")
@ViewDescriptor("work-sheet-detail-detail-view.xml")
@EditedEntityContainer("workSheetDetailDc")
public class WorkSheetDetailDetailView extends StandardDetailView<WorkSheetDetail> {
}