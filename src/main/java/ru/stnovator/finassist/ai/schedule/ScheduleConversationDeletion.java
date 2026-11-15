package ru.stnovator.finassist.ai.schedule;

import io.jmix.aitoolsflowuidata.entity.AiConversationEntity;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.event.EntityChangedEvent;
import jakarta.persistence.LockModeType;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.stnovator.finassist.entity.AiScheduleAttachment;
import ru.stnovator.finassist.entity.AiScheduleImport;
import java.time.OffsetDateTime;

/** Native conversation hard-delete detaches live FKs; receipts and file grace periods survive. */
@Component
public class ScheduleConversationDeletion {
    private final UnconstrainedDataManager data;
    public ScheduleConversationDeletion(UnconstrainedDataManager data) { this.data=data; }
    @EventListener
    @Transactional(propagation=Propagation.MANDATORY)
    public void conversationChanged(EntityChangedEvent<AiConversationEntity> event) {
        if(event.getType()!=EntityChangedEvent.Type.DELETED)return;
        var id=event.getEntityId().getValue();
        var now=OffsetDateTime.now();
        var imports=data.load(AiScheduleImport.class).query("select e from AiScheduleImport e where e.originalConversationId = :id")
                .parameter("id",id).lockMode(LockModeType.PESSIMISTIC_WRITE).list();
        for(var entity:imports) {
            if(ScheduleImportStore.active(entity)) {
                ScheduleImportStore.finish(entity,ScheduleImportStore.Status.CANCELLED);
                data.saveWithoutReload(entity);
            }
        }
        var files=data.load(AiScheduleAttachment.class).query("select e from AiScheduleAttachment e where e.originalConversationId = :id")
                .parameter("id",id).lockMode(LockModeType.PESSIMISTIC_WRITE).list();
        for(var file:files) {
            if(file.getFileDeletedAt()==null) {
                file.setRemovalRequestedAt(now);data.saveWithoutReload(file);
            }
        }
    }
}
