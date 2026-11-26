package ru.stnovator.finassist.view.addendum;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.component.grid.DataGrid;
import com.vaadin.flow.component.button.Button;
import io.jmix.flowui.model.CollectionLoader;
import ru.stnovator.finassist.view.support.ColumnFilterSupport;
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
    @ViewComponent private DataGrid<Addendum> addendaDataGrid;
    @ViewComponent private CollectionLoader<Addendum> addendaDl;
    @ViewComponent private Button clearColumnFiltersBtn;

    @Subscribe
    public void onReady(ReadyEvent event) {
        ColumnFilterSupport.bind(addendaDataGrid, addendaDl, clearColumnFiltersBtn);
    }
}
