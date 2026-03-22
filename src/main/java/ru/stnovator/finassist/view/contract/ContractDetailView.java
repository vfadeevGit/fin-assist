package ru.stnovator.finassist.view.contract;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import io.jmix.flowui.view.ViewComponent;
import org.springframework.beans.factory.annotation.Autowired;
import ru.stnovator.finassist.entity.Addendum;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.view.addendum.AddendumDetailView;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "contracts/:id", layout = MainView.class)
@ViewController("Contract.detail")
@ViewDescriptor("contract-detail-view.xml")
@EditedEntityContainer("contractDc")
public class ContractDetailView extends StandardDetailView<Contract> {
    @ViewComponent
    private DataGrid<Addendum> addendaDataGrid;

    @Autowired
    private ViewNavigators viewNavigators;

    @Subscribe("createAddendumBtn")
    public void onCreateAddendumBtnClick(final ClickEvent<Button> event) {
        viewNavigators.detailView(this, Addendum.class)
                .withViewClass(AddendumDetailView.class)
                .newEntity()
                .withQueryParameters(new QueryParameters(java.util.Map.of(
                        "parentContractId", java.util.List.of(getEditedEntity().getId().toString()),
                        "returnTo", java.util.List.of("addendumList"))))
                .navigate();
    }

    @Subscribe("editAddendumBtn")
    public void onEditAddendumBtnClick(final ClickEvent<Button> event) {
        Addendum selectedAddendum = addendaDataGrid.getSingleSelectedItem();
        if (selectedAddendum == null) {
            return;
        }

        viewNavigators.detailView(this, Addendum.class)
                .withViewClass(AddendumDetailView.class)
                .editEntity(selectedAddendum)
                .withQueryParameters(new QueryParameters(java.util.Map.of(
                        "parentContractId", java.util.List.of(getEditedEntity().getId().toString()),
                        "returnTo", java.util.List.of("addendumList"))))
                .navigate();
    }
}
