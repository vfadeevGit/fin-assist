package ru.stnovator.finassist.ai;

import io.jmix.aitools.tool.JmixAiTool;
import com.vaadin.flow.router.NotFoundException;
import com.vaadin.flow.router.RouteParameters;
import io.jmix.core.*;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.view.ViewRegistry;
import jakarta.servlet.ServletContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import java.util.*;

@Component("finassist_EntityLinksTool")
public class EntityLinksTool implements JmixAiTool {
    public static final int MAX_LINKS = 50;
    private final BusinessEntityRegistry registry;
    private final Metadata metadata;
    private final DataManager dataManager;
    private final AccessManager accessManager;
    private final ViewRegistry views;
    private final ServletContext servletContext;
    private final Messages messages;

    public EntityLinksTool(BusinessEntityRegistry registry, Metadata metadata, DataManager dataManager,
                           AccessManager accessManager, ViewRegistry views, ServletContext servletContext,
                           Messages messages) {
        this.registry = registry;
        this.metadata = metadata;
        this.dataManager = dataManager;
        this.accessManager = accessManager;
        this.views = views;
        this.servletContext = servletContext;
        this.messages = messages;
    }

    public record Link(boolean available, String url, String label) {
        static Link unavailable() { return new Link(false, null, null); }
    }

    /** Results preserve input order. An oversized request is rejected as a whole. */
    @Tool(name = "finassist_getEntityLinks", description = "Resolve detail links for up to 50 UUID record IDs of one discovered entity. Results match input order. Never invent links; unavailable results have no URL or label. Empty result means an empty or oversized request.")
    public List<Link> getEntityLinks(String entityName, List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_LINKS) return List.of();
        Optional<Class<?>> type = registry.find(entityName);
        if (type.isEmpty()) return Collections.nCopies(ids.size(), Link.unavailable());
        MetaClass meta = metadata.getClass(type.get());
        if (!canRead(meta)) return Collections.nCopies(ids.size(), Link.unavailable());
        String viewId = views.getDetailViewId(meta);
        if (!canOpen(viewId)) return Collections.nCopies(ids.size(), Link.unavailable());
        return ids.stream().map(id -> detailLink(meta, viewId, id)).toList();
    }

    @Tool(name = "finassist_getEntityListLink", description = "Resolve an accessible list link for a discovered business entity. Use the returned URL only when available is true.")
    public Link getEntityListLink(String entityName) {
        Optional<Class<?>> type = registry.find(entityName);
        if (type.isEmpty()) return Link.unavailable();
        MetaClass meta = metadata.getClass(type.get());
        if (!canRead(meta)) return Link.unavailable();
        String viewId = views.getListViewId(meta);
        if (!canOpen(viewId)) return Link.unavailable();
        return route(viewId, RouteParameters.empty(), caption(meta));
    }

    private Link detailLink(MetaClass meta, String viewId, String rawId) {
        UUID id;
        try {
            id = UUID.fromString(rawId);
            if (!id.toString().equalsIgnoreCase(rawId)) return Link.unavailable();
        } catch (IllegalArgumentException | NullPointerException e) {
            return Link.unavailable();
        }
        // Secured DataManager applies row-level constraints and excludes soft-deleted records.
        return dataManager.load(meta.getJavaClass()).id(id).fetchPlan(FetchPlan.BASE).optional()
                .map(entity -> route(viewId, new RouteParameters("id", id.toString()), label(meta, entity)))
                .orElseGet(Link::unavailable);
    }

    private boolean canRead(MetaClass meta) {
        CrudEntityContext context = new CrudEntityContext(meta);
        accessManager.applyRegisteredConstraints(context);
        return context.isReadPermitted();
    }

    private boolean canView(MetaClass meta, String property) {
        EntityAttributeContext context = new EntityAttributeContext(meta, property);
        accessManager.applyRegisteredConstraints(context);
        return context.canView();
    }

    private boolean canOpen(String viewId) {
        if (views.findViewInfo(viewId).isEmpty()) return false;
        UiShowViewContext context = new UiShowViewContext(viewId);
        accessManager.applyRegisteredConstraints(context);
        return context.isPermitted();
    }

    private Link route(String viewId, RouteParameters parameters, String label) {
        try {
            String path = views.getRouteConfiguration().getUrl(views.getViewInfo(viewId).getControllerClass(), parameters);
            return new Link(true, servletContext.getContextPath() + "/" + path, label);
        } catch (NotFoundException e) {
            return Link.unavailable();
        }
    }

    private String caption(MetaClass meta) {
        return messages.getMessage(meta.getJavaClass(), meta.getJavaClass().getSimpleName());
    }

    private String label(MetaClass meta, Object entity) {
        for (String property : List.of("name", "internalID", "number", "itemDate")) {
            if (meta.findProperty(property) != null && canView(meta, property)) {
                Object value = EntityValues.getValue(entity, property);
                if (value != null && !value.toString().isBlank()) return value.toString();
            }
        }
        // Avoid traversing instance-name relations, which could reveal inaccessible related records.
        return caption(meta);
    }
}
