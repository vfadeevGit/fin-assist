package ru.stnovator.finassist.view.support;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import io.jmix.core.*;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.backgroundtask.BackgroundWorker;
import io.jmix.flowui.view.View;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.service.*;
import java.util.UUID;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

@org.springframework.stereotype.Component
public class RelatedRecordsPresentation {
    public enum Kind { CONTRACTS, PROJECTS, SCHEDULE }
    private final RelatedRecordsService records;
    private final ProjectScheduleService schedules;
    private final RelatedDataAccess access;
    private final BackgroundWorker worker;
    private final Messages messages;
    private final DatatypeFormatter formatter;
    private final MetadataTools metadataTools;
    private final AccessManager accessManager;
    private final ViewNavigators navigators;
    private final io.jmix.flowui.Notifications notifications;

    public RelatedRecordsPresentation(RelatedRecordsService records, ProjectScheduleService schedules, RelatedDataAccess access,
                                      BackgroundWorker worker, Messages messages, DatatypeFormatter formatter,
                                      MetadataTools metadataTools, AccessManager accessManager, ViewNavigators navigators, io.jmix.flowui.Notifications notifications) {
        this.records=records; this.schedules=schedules; this.access=access; this.worker=worker;
        this.messages=messages; this.formatter=formatter; this.metadataTools=metadataTools;
        this.accessManager=accessManager; this.navigators=navigators; this.notifications=notifications;
    }
    public String message(String key) { return messages.getMessage("ru.stnovator.finassist.view.related",key); }
    public boolean permitted(Kind kind) {
        return switch(kind) {
            case CONTRACTS -> access.canRead(Contract.class,"customer");
            case PROJECTS -> access.canRead(Project.class,"contract");
            case SCHEDULE -> schedules.canReadShipments() || schedules.canReadPayments();
        };
    }
    public String title(Kind kind) { return message(kind.name().toLowerCase(java.util.Locale.ROOT)); }
    public String caption(Kind kind, Object parent) {
        Class<?> type = switch(kind) { case CONTRACTS -> Customer.class; case PROJECTS -> Contract.class; case SCHEDULE -> Project.class; };
        String property = kind == Kind.PROJECTS ? "internalID" : "name";
        return access.canRead(type,property) ? Objects.toString(EntityValues.getValue(parent,property),"") : "";
    }

    public RelatedPagePanel<?> panel(View<?> view, Kind kind, UUID parent, int page, Consumer<Integer> changed,
                                     Consumer<UUID> beforeNavigate, UUID focusChild, java.util.Map<Integer,java.time.LocalDate> anchors) {
        return switch(kind) {
            case CONTRACTS -> contracts(view,parent,page,changed,beforeNavigate,focusChild);
            case PROJECTS -> projects(view,parent,page,changed,beforeNavigate,focusChild);
            case SCHEDULE -> schedule(view,parent,page,changed,anchors);
        };
    }

    private RelatedPagePanel<Contract> contracts(View<?> view,UUID parent,int page,Consumer<Integer> changed,Consumer<UUID> beforeNavigate,UUID focus) {
        Grid<Contract> grid=grid();
        link(grid,Contract.class,"internalID",Contract::getInternalID,view,"Contract.detail",beforeNavigate,focus,entity -> records.contract(parent,entity.getId()));
        column(grid,Contract.class,"startDate",v -> formatter.formatLocalDate(v.getStartDate()));
        column(grid,Contract.class,"endDate",v -> formatter.formatLocalDate(v.getEndDate()));
        column(grid,Contract.class,"totalAmount",v -> v.getTotalAmount()==null?"":formatter.formatBigDecimal(v.getTotalAmount()));
        return new RelatedPagePanel<>(view,panelId(parent),title(Kind.CONTRACTS),grid,page,worker,p -> {
            var result=records.contracts(parent,p);
            return new RelatedPagePanel.Result<>(result.items(),result.hasNext(),() -> restoreFocus(view,grid,result.items(),parent,focus));
        },changed,this::message);
    }
    private RelatedPagePanel<Project> projects(View<?> view,UUID parent,int page,Consumer<Integer> changed,Consumer<UUID> beforeNavigate,UUID focus) {
        Grid<Project> grid=grid();
        link(grid,Project.class,"name",Project::getName,view,"Project.detail",beforeNavigate,focus,entity -> records.project(parent,entity.getId()));
        column(grid,Project.class,"lineOfBusiness",v -> v.getLineOfBusiness()==null || !access.canRead(LineOfBusiness.class,"name")
                ? "" : metadataTools.getInstanceName(v.getLineOfBusiness()));
        return new RelatedPagePanel<>(view,panelId(parent),title(Kind.PROJECTS),grid,page,worker,p -> {
            var result=records.projects(parent,p);
            return new RelatedPagePanel.Result<>(result.items(),result.hasNext(),() -> restoreFocus(view,grid,result.items(),parent,focus));
        },changed,this::message);
    }
    private RelatedPagePanel<ProjectScheduleService.Row> schedule(View<?> view,UUID parent,int page,Consumer<Integer> changed,java.util.Map<Integer,java.time.LocalDate> anchors) {
        Grid<ProjectScheduleService.Row> grid=grid();
        var date=grid.addColumn(row -> formatter.formatLocalDate(row.date())).setHeader(message("date")).setKey("date");
        var shipments=grid.addColumn(row -> row.shipments()==null?message("absent"):formatter.formatBigDecimal(row.shipments()))
                .setHeader(message("shipments")).setKey("shipments");
        var payments=grid.addColumn(row -> row.payments()==null?message("absent"):formatter.formatBigDecimal(row.payments()))
                .setHeader(message("payments")).setKey("payments");
        return new RelatedPagePanel<>(view,panelId(parent),title(Kind.SCHEDULE),grid,page,worker,p -> {
            var result=schedules.schedule(parent,anchors.get(p));
            return new RelatedPagePanel.Result<>(result.rows(),result.hasNext(),() -> {
                if(!result.rows().isEmpty()) anchors.put(p+1,result.rows().getLast().date());
                date.setFooter(message("total"));
                shipments.setRenderer(new com.vaadin.flow.data.renderer.TextRenderer<>(row -> !result.shipmentsAvailable()?message("restricted"):
                        row.shipments()==null?message("absent"):formatter.formatBigDecimal(row.shipments())));
                payments.setRenderer(new com.vaadin.flow.data.renderer.TextRenderer<>(row -> !result.paymentsAvailable()?message("restricted"):
                        row.payments()==null?message("absent"):formatter.formatBigDecimal(row.payments())));
                shipments.setFooter(result.shipmentsAvailable()?formatter.formatBigDecimal(result.shipmentTotal()):message("restricted"));
                payments.setFooter(result.paymentsAvailable()?formatter.formatBigDecimal(result.paymentTotal()):message("restricted"));
            });
        },changed,this::message);
    }
    private <T> Grid<T> grid() {
        Grid<T> grid=new Grid<>(); grid.addThemeVariants(GridVariant.ROW_STRIPES,GridVariant.NO_BORDER);
        grid.setSelectionMode(Grid.SelectionMode.NONE); return grid;
    }
    private <T> void column(Grid<T> grid,Class<T> type,String property,Function<T,String> text) {
        if(access.canRead(type,property)) grid.addColumn(text::apply).setHeader(messages.getMessage(type,type.getSimpleName()+"."+property)).setKey(property);
    }
    private <T> void link(Grid<T> grid,Class<T> type,String property,Function<T,String> text,View<?> owner,String viewId,
                          Consumer<UUID> beforeNavigate,UUID focus,Function<T,T> reload) {
        if(!access.canRead(type,property)) return;
        grid.addColumn(new ComponentRenderer<Component,T>(entity -> {
            var context=new UiShowViewContext(viewId); accessManager.applyRegisteredConstraints(context);
            if(!context.isPermitted()) return new Span(text.apply(entity));
            UUID id=(UUID)EntityValues.getId(entity);
            Button link=new Button(text.apply(entity), e -> {
                try {
                    T current=reload.apply(entity);
                    var currentView=new UiShowViewContext(viewId); accessManager.applyRegisteredConstraints(currentView);
                    if(!currentView.isPermitted()) throw new IllegalStateException("View unavailable");
                    beforeNavigate.accept(id);
                    navigators.detailView(owner,type).editEntity(current).withViewId(viewId).withBackwardNavigation(true).navigate();
                } catch(IllegalStateException | io.jmix.core.security.AccessDeniedException ex) {
                    notifications.create(message("unavailable")).show();
                }
            });
            link.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            link.setId("related-link-"+id);
            if(id.equals(focus)) link.addAttachListener(e -> link.focus());
            return link;
        })).setHeader(messages.getMessage(type,type.getSimpleName()+"."+property)).setKey(property);
    }
    private <T> void restoreFocus(View<?> owner,Grid<T> grid,java.util.List<T> rows,UUID parent,UUID child) {
        if(child==null) return;
        for(int i=0;i<rows.size();i++) {
            if(child.equals(EntityValues.getId(rows.get(i)))) {
                grid.scrollToIndex(i);
                grid.getElement().executeJs("requestAnimationFrame(() => this.querySelector('#related-link-' + $0)?.focus())",child.toString());
                return;
            }
        }
        if(owner instanceof RelatedListView<?> list) list.focusParent(parent);
    }
    public static String panelId(UUID parent) { return "related-"+parent; }
}
