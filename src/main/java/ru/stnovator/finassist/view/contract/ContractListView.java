package ru.stnovator.finassist.view.contract;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.*;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.view.main.MainView;


@Route(value = "contracts", layout = MainView.class)
@ViewController("Contract.list")
@ViewDescriptor("contract-list-view.xml")
@LookupComponent("contractsDataGrid")
@DialogMode(width = "64em")
public class ContractListView extends StandardListView<Contract> {
}