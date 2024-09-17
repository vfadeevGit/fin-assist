package ru.stnovator.finassist.view.worksheetdetail;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.model.DataContext;
import io.jmix.flowui.view.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.stnovator.finassist.entity.WorkSheetDetail;
import ru.stnovator.finassist.view.main.MainView;

import java.util.Set;

@Route(value = "workSheetDetails/:id", layout = MainView.class)
@ViewController("WorkSheetDetail.detail")
@ViewDescriptor("work-sheet-detail-detail-view.xml")
@EditedEntityContainer("workSheetDetailDc")
@DialogMode
public class WorkSheetDetailDetailView extends StandardDetailView<WorkSheetDetail> {
    @ViewComponent
    private DataContext dataContext;

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        WorkSheetDetail workSheetDetail = getEditedEntity();
    }

}