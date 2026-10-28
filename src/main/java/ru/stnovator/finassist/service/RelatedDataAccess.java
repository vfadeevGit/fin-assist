package ru.stnovator.finassist.service;

import io.jmix.core.*;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** Shared permission checks, always evaluated against the calling user's context. */
@Component
public class RelatedDataAccess {
    private final AccessManager accessManager;
    private final Metadata metadata;
    private final DataManager dataManager;

    public RelatedDataAccess(AccessManager accessManager, Metadata metadata, DataManager dataManager) {
        this.accessManager = accessManager;
        this.metadata = metadata;
        this.dataManager = dataManager;
    }

    public boolean canRead(Class<?> type, String... attributes) {
        var context = new CrudEntityContext(metadata.getClass(type));
        accessManager.applyRegisteredConstraints(context);
        if (!context.isReadPermitted()) return false;
        for (String attribute : attributes) {
            var attributeContext = new EntityAttributeContext(metadata.getClass(type), attribute);
            accessManager.applyRegisteredConstraints(attributeContext);
            if (!attributeContext.canView()) return false;
        }
        return true;
    }

    public <T> T requireSource(Class<T> type, UUID id) {
        if (!canRead(type)) throw new IllegalStateException("Related source unavailable");
        return dataManager.load(type).id(id).fetchPlan(FetchPlan.BASE).optional()
                .orElseThrow(() -> new IllegalStateException("Related source unavailable"));
    }
}
