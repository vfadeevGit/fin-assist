package ru.stnovator.finassist.security;

import io.jmix.core.AccessConstraintsRegistry;
import io.jmix.core.accesscontext.*;
import io.jmix.core.constraint.*;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.AccessDeniedException;
import io.jmix.data.accesscontext.*;
import io.jmix.data.QueryTransformerFactory;
import io.jmix.data.impl.jpql.NodesFinder;
import io.jmix.data.impl.jpql.Parser;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.tree.TreeVisitor;
import io.jmix.data.impl.jpql.tree.QueryNode;
import jakarta.annotation.PostConstruct;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;
import ru.stnovator.finassist.ai.schedule.ScheduleImportOwnership;
import ru.stnovator.finassist.entity.*;

/** Mandatory boundaries, including users with generic all-entity resource policies. */
@Component
@NullMarked
public class AiScheduleImportConstraints {
    private final AccessConstraintsRegistry registry;
    private final ScheduleImportOwnership ownership;
    private final QueryTransformerFactory queries;
    public AiScheduleImportConstraints(AccessConstraintsRegistry registry,ScheduleImportOwnership ownership,
                                       QueryTransformerFactory queries) {
        this.registry=registry;this.ownership=ownership;this.queries=queries;
    }
    private void rejectTechnicalProbes(ReadEntityQueryContext context) {
        String jpql=context.getResultQuery().getQueryString();
        var parser=queries.parser(jpql);
        boolean referencesTechnical=parser.getAllEntityNames().stream().anyMatch(name->name.equals("AiScheduleImport") || name.equals("AiScheduleAttachment"));
        if(!referencesTechnical)return;
        // Technical state is available only as a single, owned root. Mixed roots and nested
        // SELECTs can otherwise reveal another owner's fields through the result's existence.
        if(!technical(context.getEntityClass()) || !parser.getEntityName().equals(context.getEntityClass().getName()) || parser.isQueryWithJoins())
            throw new AccessDeniedException("entity","schedule-import","read");
        try {
            var nestedQueries=NodesFinder.of(QueryNode.class);
            new TreeVisitor().visit(Parser.parse(jpql,true),nestedQueries);
            if(nestedQueries.getFoundNodes().size()>1)throw new AccessDeniedException("entity","schedule-import","read");
        } catch(RecognitionException ex) {
            throw new AccessDeniedException("entity","schedule-import","read");
        }
    }
    private static boolean technical(MetaClass type) {
        return AiScheduleImport.class.equals(type.getJavaClass()) || AiScheduleAttachment.class.equals(type.getJavaClass());
    }
    @PostConstruct
    void register() {
        registry.register(new EntityOperationConstraint<CrudEntityContext>() {
            public Class<CrudEntityContext> getContextType(){return CrudEntityContext.class;}
            public void applyTo(CrudEntityContext context){
                if(technical(context.getEntityClass())) {context.setCreateDenied();context.setUpdateDenied();context.setDeleteDenied();}
            }
        });
        registry.register(new RowLevelConstraint<ReadEntityQueryContext>() {
            public Class<ReadEntityQueryContext> getContextType(){return ReadEntityQueryContext.class;}
            public void applyTo(ReadEntityQueryContext context){
                rejectTechnicalProbes(context);
                if(technical(context.getEntityClass())) context.addJoinAndWhere("left join {E}.conversation scheduleImportOwnerConversation",
                        "{E}.owner = :current_user_username and ({E}.conversation is null or scheduleImportOwnerConversation.username = :current_user_username)");
            }
        });
        registry.register(new InMemoryConstraint<InMemoryCrudEntityContext>() {
            public Class<InMemoryCrudEntityContext> getContextType(){return InMemoryCrudEntityContext.class;}
            public void applyTo(InMemoryCrudEntityContext context){
                if(!technical(context.getEntityClass()))return;
                context.addReadPredicate((entity,ctx)-> {
                    if(entity instanceof AiScheduleImport e)return ownership.owns(e.getOwner(),e.getOriginalConversationId());
                    var e=(AiScheduleAttachment)entity;return ownership.owns(e.getOwner(),e.getOriginalConversationId());
                });
                context.addCreatePredicate((entity,ctx)->false);context.addUpdatePredicate((entity,ctx)->false);context.addDeletePredicate((entity,ctx)->false);
            }
        });
        // Scalar JPQL can mention technical entities through joins and does not apply in-memory predicates.
        registry.register(new EntityOperationConstraint<LoadValuesAccessContext>() {
            public Class<LoadValuesAccessContext> getContextType(){return LoadValuesAccessContext.class;}
            public void applyTo(LoadValuesAccessContext context){if(context.getEntityClasses().stream().anyMatch(AiScheduleImportConstraints::technical))throw new AccessDeniedException("entity","schedule-import","read");}
        });
    }
}
