package ru.stnovator.finassist.view.contract;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.*;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.view.main.MainView;
import ru.stnovator.finassist.view.support.RelatedListView;
import ru.stnovator.finassist.view.support.RelatedRecordsPresentation;

@Route(value = "contracts", layout = MainView.class)
@ViewController("Contract.list")
@ViewDescriptor("contract-list-view.xml")
@LookupComponent("contractsDataGrid")
@DialogMode(width = "64em")
public class ContractListView extends RelatedListView<Contract> {
    @Override protected String relatedPrefix() { return "contracts"; }
    @Override protected RelatedRecordsPresentation.Kind relatedKind() { return RelatedRecordsPresentation.Kind.PROJECTS; }
}
