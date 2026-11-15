package ru.stnovator.finassist.ai.schedule;

import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ru.stnovator.finassist.entity.*;
import java.time.OffsetDateTime;

/** System context is confined to technical import tables and their files. */
@Component
@EnableScheduling
public class ScheduleImportCleanup {
    private static final Logger log=LoggerFactory.getLogger(ScheduleImportCleanup.class);
    private final UnconstrainedDataManager data;
    private final SystemAuthenticator authentication;
    private final ScheduleImportProperties properties;
    private final ScheduleAttachmentService attachments;
    private final TransactionTemplate transaction;
    public ScheduleImportCleanup(UnconstrainedDataManager data,SystemAuthenticator authentication,
                                 ScheduleImportProperties properties,ScheduleAttachmentService attachments,
                                 PlatformTransactionManager transactionManager) {
        this.data=data;this.authentication=authentication;this.properties=properties;this.attachments=attachments;
        this.transaction=new TransactionTemplate(transactionManager);
    }
    @Scheduled(scheduler="core_ThreadPoolTaskScheduler",fixedDelayString="${finassist.schedule-import.cleanup-delay-ms:60000}",initialDelayString="${finassist.schedule-import.cleanup-delay-ms:60000}")
    public void run() {
        authentication.runWithSystem(()-> {
            var now=OffsetDateTime.now();
            var stale=data.load(AiScheduleImport.class).query("select e from AiScheduleImport e where e.touchedAt < :cutoff and e.status in ('COLLECTING','PREVIEW_READY')")
                    .parameter("cutoff",now.minus(properties.getDraftTtl())).list();
            for(var candidate:stale) transaction.executeWithoutResult(status->{
                var entity=data.load(AiScheduleImport.class).id(candidate.getId()).lockMode(LockModeType.PESSIMISTIC_WRITE).optional().orElse(null);
                if(entity!=null && ScheduleImportStore.active(entity) && entity.getTouchedAt().plus(properties.getDraftTtl()).isBefore(now)) {
                    ScheduleImportStore.finish(entity,ScheduleImportStore.Status.EXPIRED);data.saveWithoutReload(entity);
                }
            });
            var candidates=data.load(AiScheduleAttachment.class).query("select e from AiScheduleAttachment e where e.fileDeletedAt is null").list();
            for(var candidate:candidates) {
                try { transaction.executeWithoutResult(status->{
                    var entity=data.load(AiScheduleAttachment.class).id(candidate.getId()).lockMode(LockModeType.PESSIMISTIC_WRITE).optional().orElse(null);
                    if(entity==null || entity.getFileDeletedAt()!=null)return;
                    if(entity.getRemovalRequestedAt()!=null && entity.getRemovalRequestedAt().plus(properties.getFileRetention()).isAfter(now))return;
                    var references=attachments.references(entity.getId());
                    if(references.stream().anyMatch(ScheduleImportStore::active))return;
                    if(references.isEmpty()) {
                        if(entity.getRemovalRequestedAt()==null && entity.getStagedAt().plus(properties.getDraftTtl()).plus(properties.getFileRetention()).isAfter(now))return;
                    } else if(references.stream().anyMatch(e->e.getTerminalAt()==null || e.getTerminalAt().plus(properties.getFileRetention()).isAfter(now)))return;
                    attachments.deleteFile(entity);data.saveWithoutReload(entity);
                }); }catch(RuntimeException ex) {log.warn("Schedule attachment cleanup will retry {}",candidate.getId(),ex);}
            }
        });
    }
}
