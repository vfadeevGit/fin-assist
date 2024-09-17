package ru.stnovator.finassist.view.worksheet;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.app.inputdialog.DialogActions;
import io.jmix.flowui.app.inputdialog.DialogOutcome;
import io.jmix.flowui.app.inputdialog.InputParameter;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionPropertyContainer;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;
import ru.stnovator.finassist.entity.WorkSheet;
import ru.stnovator.finassist.entity.WorkSheetDetail;
import ru.stnovator.finassist.view.main.MainView;

import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;

@Route(value = "workSheets/:id", layout = MainView.class)
@ViewController("WorkSheet.detail")
@ViewDescriptor("work-sheet-detail-view.xml")
@EditedEntityContainer("workSheetDc")
public class WorkSheetDetailView extends StandardDetailView<WorkSheet> {
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private Notifications notifications;
    @ViewComponent
    private CollectionPropertyContainer<WorkSheetDetail> detailsDc;

    @Subscribe(id = "btnFillDetails", subject = "clickListener")
    public void onBtnFillDetailsClick(final ClickEvent<JmixButton> event) {
        dialogs.createInputDialog(this)
                .withHeader("Введите период")
                .withLabelsPosition(Dialogs.InputDialogBuilder.LabelsPosition.TOP)
                .withParameters(
                        InputParameter.localDateParameter("beginDate")
                                .withLabel("Дата начала"),
                        InputParameter.localDateParameter("endDate")
                                .withLabel("Дата окончания")
                )
                .withActions(DialogActions.OK_CANCEL)
                .withCloseListener(closeEvent -> {
                    if(!closeEvent.closedWith(DialogOutcome.OK)) {
                        return;
                    }
                    LocalDate beginDate = closeEvent.getValue("beginDate");
                    LocalDate endDate = closeEvent.getValue("endDate");
                    if (endDate.isBefore(beginDate)) {
                        notifications.create("Ошибка: Дата начала позднее даты окончания").withType(Notifications.Type.WARNING).show();
                    }
                    fillDetailsDataGridForPeriod(beginDate, endDate);
                })
                .open();
    }

    private void fillDetailsDataGridForPeriod(LocalDate beginDate, LocalDate endDate) {
        ArrayList<LocalDate> arrayMonths = new ArrayList<>();
        LocalDate firstDate = beginDate.withDayOfMonth(1);
        arrayMonths.add(firstDate);
        Period diff = Period.between(beginDate, endDate);
        for (int i=1; i <= diff.getMonths(); i++) {
            arrayMonths.add(firstDate.plusMonths(i));
        }
        arrayMonths.stream().forEach(System.out::println);
    }
}