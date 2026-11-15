package ru.stnovator.finassist.ai.schedule;

import io.jmix.core.*;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.stnovator.finassist.entity.*;
import java.io.*;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class ScheduleAttachmentService {
    private final UnconstrainedDataManager data;
    private final ScheduleImportOwnership ownership;
    private final ScheduleImportProperties properties;
    private final FileStorageLocator storages;
    public ScheduleAttachmentService(UnconstrainedDataManager data,ScheduleImportOwnership ownership,
                                     ScheduleImportProperties properties,FileStorageLocator storages) {
        this.data=data;this.ownership=ownership;this.properties=properties;this.storages=storages;
    }
    @Transactional
    public UUID stage(UUID conversationId,String fileName,InputStream input) {
        var conversation=ownership.conversation(conversationId);
        if(fileName==null || fileName.length()>255 || fileName.contains("/") || fileName.contains("\\") || fileName.chars().anyMatch(Character::isISOControl)
                || !fileName.toLowerCase(Locale.ROOT).matches(".+\\.(csv|xlsx|xls)")) throw new IllegalArgumentException("Unsupported file name");
        long limit=properties.getMaxFileBytes();
        if(limit<1 || limit>=Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid file limit");
        byte[] bytes;
        try {bytes=input.readNBytes((int)limit+1);}catch(IOException ex){throw new UncheckedIOException(ex);}
        if(bytes.length>limit) throw new IllegalArgumentException("File size limit exceeded");
        var storage=storages.getDefault();var ref=storage.saveStream(fileName,new ByteArrayInputStream(bytes));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {if(status!=STATUS_COMMITTED) storage.removeFile(ref);}
        });
        var attachment=data.create(AiScheduleAttachment.class);attachment.setOwner(ownership.username());attachment.setConversation(conversation);attachment.setOriginalConversationId(conversationId);
        attachment.setFileName(fileName);attachment.setFileRef(ref);attachment.setSizeBytes((long)bytes.length);
        return data.save(attachment).getId();
    }
    @Transactional
    public InputStream open(UUID attachmentId) {
        var attachment=owned(attachmentId);
        if(attachment.getFileDeletedAt()!=null || attachment.getRemovalRequestedAt()!=null) throw new IllegalStateException("File is unavailable");
        return storages.getByName(attachment.getFileRef().getStorageName()).openStream(attachment.getFileRef());
    }
    @Transactional
    public void remove(UUID attachmentId) {
        var attachment=ownedForRemoval(attachmentId);var references=references(attachmentId);
        if(references.stream().anyMatch(ScheduleImportStore::active)) throw new IllegalStateException("Attachment is in active use");
        if(attachment.getFileDeletedAt()!=null)return;
        if(attachment.getRemovalRequestedAt()==null)attachment.setRemovalRequestedAt(OffsetDateTime.now());
        boolean retained=references.stream().anyMatch(e->e.getTerminalAt()==null || e.getTerminalAt().plus(properties.getFileRetention()).isAfter(OffsetDateTime.now()));
        if(attachment.getConversation()==null && attachment.getRemovalRequestedAt().plus(properties.getFileRetention()).isAfter(OffsetDateTime.now()))retained=true;
        if(!retained) deleteFile(attachment);
        data.saveWithoutReload(attachment);
    }
    AiScheduleAttachment reference(UUID id,UUID conversationId,UUID operationId) {
        var attachment=owned(id);
        if(!attachment.getOriginalConversationId().equals(conversationId)) throw new SecurityException("Attachment belongs to another conversation");
        if(attachment.getFileDeletedAt()!=null || attachment.getRemovalRequestedAt()!=null) throw new IllegalStateException("File is unavailable");
        if(references(id).stream().anyMatch(e->!e.getId().equals(operationId))) throw new IllegalStateException("Attachment already belongs to an import");
        return attachment;
    }
    private AiScheduleAttachment ownedForRemoval(UUID id) {
        var attachment=locked(id);
        ownership.receipt(attachment.getOwner(),attachment.getOriginalConversationId());
        return attachment;
    }
    private AiScheduleAttachment owned(UUID id) {
        var attachment=locked(id);
        ownership.check(attachment.getOwner(),attachment.getOriginalConversationId());return attachment;
    }
    private AiScheduleAttachment locked(UUID id) {
        var attachment=data.load(AiScheduleAttachment.class).id(id).lockMode(LockModeType.PESSIMISTIC_WRITE).optional()
                .orElseThrow(()->new SecurityException("Attachment is unavailable"));
        return attachment;
    }
    List<AiScheduleImport> references(UUID id) {
        return data.load(AiScheduleImport.class).query("select e from AiScheduleImport e where e.attachment.id = :id").parameter("id",id).list();
    }
    void deleteFile(AiScheduleAttachment attachment) {
        var storage=storages.getByName(attachment.getFileRef().getStorageName());
        storage.removeFile(attachment.getFileRef());attachment.setFileDeletedAt(OffsetDateTime.now());
    }
}
