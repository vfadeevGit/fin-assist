package ru.stnovator.finassist.view.worksheet;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.*;
import ru.stnovator.finassist.entity.WorkSheet;
import ru.stnovator.finassist.view.main.MainView;


@Route(value = "workSheets", layout = MainView.class)
@ViewController("WorkSheet.list")
@ViewDescriptor("work-sheet-list-view.xml")
@LookupComponent("workSheetsDataGrid")
@DialogMode(width = "64em")
public class WorkSheetListView extends StandardListView<WorkSheet> {
}