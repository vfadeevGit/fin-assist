package ru.stnovator.finassist.view.support;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import io.jmix.core.Sort;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;

/** Shared row-details lifecycle for the three existing standalone list views. */
public abstract class RelatedListView<E> extends StandardListView<E> {
    @Autowired private RelatedRecordsPresentation presentation;
    @Autowired private CurrentAuthentication authentication;
    private DataGrid<E> grid;
    private CollectionLoader<E> loader;
    private final Map<UUID,Integer> expanded = new LinkedHashMap<>();
    private final Map<UUID,RelatedPagePanel<?>> panels = new HashMap<>();
    private final Map<UUID,Button> toggles = new HashMap<>();
    private final Map<UUID,Map<Integer,java.time.LocalDate>> dateAnchors=new HashMap<>();
    private boolean restoring;
    private UUID focusParent;
    private UUID focusChild;
    private UUID focusToggle;

    protected abstract String relatedPrefix();
    protected abstract RelatedRecordsPresentation.Kind relatedKind();

    private record SortState(String key, SortDirection direction) {}
    private record State(String user,Map<String,List<String>> query,int first,int max,Sort sort,List<SortState> columns,
                         Set<UUID> selected,Map<UUID,Integer> expanded,Map<UUID,Map<Integer,java.time.LocalDate>> dateAnchors,UUID focusParent,UUID focusChild) {}
    private static class States { private final Map<String,State> views = new HashMap<>(); }

    @Subscribe
    public void onRelatedInit(InitEvent event) {
        grid=(DataGrid<E>)io.jmix.flowui.component.UiComponentUtils.getComponent(this,relatedPrefix()+"DataGrid");
        loader=ViewControllerUtils.getViewData(this).getLoader(relatedPrefix()+"Dl");
        grid.setDetailsVisibleOnClick(false);
        grid.getColumnByKey("relatedDetails").setRenderer(new ComponentRenderer<>(this::expander));
        grid.setItemDetailsRenderer(new ComponentRenderer<>(this::details));
        loader.addPreLoadListener(e -> { if(!restoring) clearExpansions(); });
        grid.addSortListener(e -> { if(!restoring) clearExpansions(); });
    }

    @Subscribe
    public void onRelatedReady(ReadyEvent event) {
        boolean available=getSelectionHandler().isEmpty() && presentation.permitted(relatedKind());
        grid.getColumnByKey("relatedDetails").setVisible(available);
        if(!available) { clearExpansions(); return; }
        State state=states().views.remove(getClass().getName());
        if(state==null || !state.user().equals(authentication.getUser().getUsername()) || !state.query().equals(query())) return;
        restoring=true;
        try {
            loader.setFirstResult(state.first()); loader.setMaxResults(state.max()); loader.setSort(state.sort());
            List<GridSortOrder<E>> orders=state.columns().stream().filter(s -> grid.getColumnByKey(s.key())!=null)
                    .map(s -> new GridSortOrder<>(grid.getColumnByKey(s.key()),s.direction())).toList();
            grid.sort(orders); loader.load();
            while(loader.getContainer().getItems().isEmpty() && loader.getFirstResult()>0) {
                loader.setFirstResult(Math.max(0,loader.getFirstResult()-loader.getMaxResults())); loader.load();
            }
            focusParent=state.focusParent(); focusChild=state.focusChild();
            state.dateAnchors().forEach((id,anchors) -> dateAnchors.put(id,new HashMap<>(anchors)));
            grid.deselectAll();
            for(E item:loader.getContainer().getItems()) {
                UUID id=id(item);
                if(state.selected().contains(id)) grid.select(item);
                if(state.expanded().containsKey(id)) {
                    expanded.put(id,state.expanded().get(id));
                    grid.setDetailsVisible(item,true);
                }
            }
            if(focusParent!=null) {
                List<E> items=loader.getContainer().getItems();
                boolean found=false;
                for(int i=0;i<items.size();i++) if(id(items.get(i)).equals(focusParent)) { grid.scrollToIndex(i); found=true; break; }
                if(!found) grid.focus();
            }
        } finally { restoring=false; }
    }

    @Subscribe
    public void onRelatedBeforeClose(BeforeCloseEvent event) {
        if(getSelectionHandler().isPresent()) return;
        boolean detail=event.getCloseAction() instanceof NavigateCloseAction navigate
                && StandardDetailView.class.isAssignableFrom(navigate.getBeforeLeaveEvent().getNavigationTarget());
        if(detail) {
            Set<UUID> selected=new HashSet<>(); grid.getSelectedItems().forEach(e -> selected.add(id(e)));
            List<SortState> sort=grid.getSortOrder().stream().map(s -> new SortState(s.getSorted().getKey(),s.getDirection())).toList();
            states().views.put(getClass().getName(),new State(authentication.getUser().getUsername(),query(),loader.getFirstResult(),
                    loader.getMaxResults(),loader.getSort(),sort,selected,new LinkedHashMap<>(expanded),copyAnchors(),focusParent,focusChild));
        } else { states().views.remove(getClass().getName()); }
        panels.values().forEach(RelatedPagePanel::cancel);
    }

    @Subscribe
    public void onRelatedAfterClose(AfterCloseEvent event) { panels.values().forEach(RelatedPagePanel::cancel); }

    private Component expander(E item) {
        UUID id=id(item);
        Button button=new Button(); button.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        button.setId("expand-"+id);
        button.getElement().setAttribute("aria-controls",RelatedRecordsPresentation.panelId(id));
        button.getElement().setAttribute("aria-label",presentation.message("toggle")+" "+presentation.title(relatedKind())+" "+presentation.caption(relatedKind(),item));
        button.getElement().addEventListener("click", e -> {}).addEventData("event.stopPropagation()");
        toggles.put(id,button); updateToggle(button,expanded.containsKey(id));
        button.addClickListener(e -> {
            boolean open=!grid.isDetailsVisible(item);
            if(open && (getSelectionHandler().isPresent() || !presentation.permitted(relatedKind()))) return;
            focusToggle=id;
            if(open) expanded.put(id,0);
            else {
                expanded.remove(id); dateAnchors.remove(id); var panel=panels.remove(id); if(panel!=null) panel.cancel();
                button.focus();
            }
            grid.setDetailsVisible(item,open); updateToggle(button,open);
        });
        if(id.equals(focusToggle) || (id.equals(focusParent) && focusChild==null)) button.addAttachListener(e -> button.focus());
        return button;
    }

    private Component details(E item) {
        UUID parent=id(item);
        // Rendering can be requested again by Grid; reuse only while this row is expanded.
        return panels.computeIfAbsent(parent,id -> {
            var panel=presentation.panel(this,relatedKind(),id,expanded.getOrDefault(id,0),
                    page -> { if(expanded.containsKey(id)) expanded.put(id,page); },
                    child -> { focusParent=id; focusChild=child; focusToggle=null; },id.equals(focusParent)?focusChild:null,
                    dateAnchors.computeIfAbsent(id,key -> new HashMap<>()));
            panel.setAccessibleName(presentation.title(relatedKind())+" "+presentation.caption(relatedKind(),item));
            return panel;
        });
    }

    private void clearExpansions() {
        panels.values().forEach(RelatedPagePanel::cancel); panels.clear();
        if(grid.getItems()!=null) for(E item:grid.getItems().getItems()) grid.setDetailsVisible(item,false);
        expanded.clear(); dateAnchors.clear(); toggles.values().forEach(button -> updateToggle(button,false)); toggles.clear();
        focusParent=null; focusChild=null; focusToggle=null;
    }
    void focusParent(UUID parent) {
        focusChild=null;
        Button toggle=toggles.get(parent);
        if(toggle!=null) toggle.focus();
        else grid.focus();
    }
    private Map<UUID,Map<Integer,java.time.LocalDate>> copyAnchors() {
        Map<UUID,Map<Integer,java.time.LocalDate>> copy=new HashMap<>();
        dateAnchors.forEach((id,anchors) -> copy.put(id,new HashMap<>(anchors)));
        return copy;
    }
    private void updateToggle(Button button,boolean open) {
        button.setIcon((open?VaadinIcon.ANGLE_DOWN:VaadinIcon.ANGLE_RIGHT).create());
        button.getElement().setAttribute("aria-expanded",Boolean.toString(open));
    }
    private UUID id(E entity) { return (UUID)EntityValues.getId(entity); }
    private Map<String,List<String>> query() {
        return Map.copyOf(UI.getCurrent().getInternals().getActiveViewLocation().getQueryParameters().getParameters());
    }
    private States states() {
        UI ui=UI.getCurrent(); States states=ComponentUtil.getData(ui,States.class);
        if(states==null) { states=new States(); ComponentUtil.setData(ui,States.class,states); }
        return states;
    }
}
