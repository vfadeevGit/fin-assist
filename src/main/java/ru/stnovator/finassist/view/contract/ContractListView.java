package ru.stnovator.finassist.view.contract;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.*;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.view.main.MainView;

@Route(value = "contracts", layout = MainView.class)
@ViewController("Contract.list")
@ViewDescriptor("contract-list-view.xml")
@LookupComponent("contractsDataGrid")
@DialogMode(width = "64em")
public class ContractListView extends StandardListView<Contract> {
    @ViewComponent
    private JmixButton createBtn;

    @Subscribe(id = "createBtn", subject = "clickListener")
    public void onCreateBtnClick(final ClickEvent<JmixButton> event) {
//        cre
    }


}
