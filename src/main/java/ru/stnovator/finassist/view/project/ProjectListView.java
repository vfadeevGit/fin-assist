package ru.stnovator.finassist.view.project;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.*;
import ru.stnovator.finassist.entity.Project;
import ru.stnovator.finassist.view.main.MainView;
import ru.stnovator.finassist.view.support.RelatedListView;
import ru.stnovator.finassist.view.support.RelatedRecordsPresentation;

@Route(value = "projects", layout = MainView.class)
@ViewController("Project.list")
@ViewDescriptor("project-list-view.xml")
@LookupComponent("projectsDataGrid")
@DialogMode(width = "64em")
public class ProjectListView extends RelatedListView<Project> {
    @Override protected String relatedPrefix() { return "projects"; }
    @Override protected RelatedRecordsPresentation.Kind relatedKind() { return RelatedRecordsPresentation.Kind.SCHEDULE; }
}
