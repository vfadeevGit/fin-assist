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
import io.jmix.flowui.model.DataContext;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;
import ru.stnovator.finassist.entity.WorkSheet;
import ru.stnovator.finassist.entity.WorkSheetDetail;
import ru.stnovator.finassist.view.main.MainView;

import java.math.BigDecimal;
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
    @ViewComponent
    private DataContext dataContext;

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
        //calculate months inside full years and add months
        int diff = (Period.between(beginDate, endDate).getYears() > 0 ? 12 * Period.between(beginDate, endDate).getYears() : 0)
                + Period.between(beginDate, endDate).getMonths();
        for (int i=1; i <= diff; i++) {
            arrayMonths.add(firstDate.plusMonths(i));
        }
//        arrayMonths.stream().forEach(System.out::println);
        //TODO think of making this method separate
        WorkSheet workSheet = getEditedEntity();
        for (LocalDate dateDetails : arrayMonths) {
            if (detailsDc.getItems().stream().noneMatch(detail -> detail.getDateInterval().equals(dateDetails))) {
                WorkSheetDetail detail = dataContext.create(WorkSheetDetail.class);
                detail.setWorkSheet(workSheet);
                detail.setDateInterval(dateDetails);
                detail.setSum(BigDecimal.valueOf(0));
                detailsDc.getMutableItems().add(detail);
            }
        }
    }
}