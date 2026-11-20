package ru.stnovator.finassist.ai.schedule;

import io.jmix.aitoolsflowuidata.entity.AiChatMessageEntity;
import io.jmix.aitoolsflowuidata.entity.AiChatMessageEntityType;
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
    public record SubmittedAttachment(UUID id,String fileName,long sizeBytes,boolean available,
                                      UUID sourceMessageId,OffsetDateTime submittedAt) {
        public SubmittedAttachment(UUID id,String fileName,long sizeBytes) {
            this(id,fileName,sizeBytes,true,null,null);
        }
    }
    public record SubmittedPage(List<SubmittedAttachment> files,long totalCount,Integer nextOffset) {}
    private static final int PAGE_SIZE = 12;
    private static final String SUBMITTED = """
            from AiScheduleAttachment e, aitls_AiChatMessageEntity m
            where e.originalConversationId=:conversation and e.owner=:owner
              and m.id=e.sourceMessageId and m.conversation.id=:conversation and m.type=:userType
            """;
    private static final String THROUGH = """
             and (m.createdDate<:created or (m.createdDate=:created and m.id<=:message))
            """;
    @Transactional(readOnly = true)
    public SubmittedPage listSubmitted(UUID conversationId, UUID throughUserMessageId) {
        return listSubmitted(conversationId,throughUserMessageId,0);
    }
    @Transactional(readOnly = true)
    public SubmittedPage listSubmitted(UUID conversationId,UUID throughUserMessageId,int offset) {
        return submittedPage(conversationId,ownership.userMessage(throughUserMessageId,conversationId),offset);
    }
    /** Conversation overview includes every submitted message and excludes staged uploads. */
    @Transactional(readOnly = true)
    public SubmittedPage listSubmitted(UUID conversationId,int offset) {
        ownership.conversation(conversationId);
        return submittedPage(conversationId,null,offset);
    }
    private SubmittedPage submittedPage(UUID conversationId,AiChatMessageEntity through,int offset) {
        if (offset<0 || offset>Integer.MAX_VALUE-PAGE_SIZE) throw new IllegalArgumentException("Invalid file offset");
        Map<String,Object> params=new HashMap<>(Map.of("conversation",conversationId,"owner",ownership.username(),
                "userType",AiChatMessageEntityType.USER.getId()));
        String from=SUBMITTED;
        if (through!=null) {
            from+=THROUGH;
            params.put("created",through.getCreatedDate());
            params.put("message",through.getId());
        }
        long total=data.loadValue("select count(e) "+from,Long.class).setParameters(params).one();
        var files=data.loadValues("select e, m.createdDate "+from+" order by m.createdDate desc, m.id desc, e.id desc")
                .properties("attachment","submittedAt").setParameters(params).firstResult(offset).maxResults(PAGE_SIZE)
                .list().stream().map(row->metadata(row.getValue("attachment"),row.getValue("submittedAt"))).toList();
        int next=offset+files.size();
        return new SubmittedPage(files,total,next<total && !files.isEmpty()?next:null);
    }
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
    @Transactional(noRollbackFor = FileStorageException.class)
    public InputStream open(UUID attachmentId) {
        var attachment=owned(attachmentId);
        if(attachment.getFileDeletedAt()!=null || attachment.getRemovalRequestedAt()!=null) throw new IllegalStateException("File is unavailable");
        return storages.getByName(attachment.getFileRef().getStorageName()).openStream(attachment.getFileRef());
    }
    public record SubmittedDownload(InputStream stream, String fileName, long sizeBytes) {
    }

    /** Checks persisted submission metadata under the attachment lock at download time. */
    @Transactional
    public SubmittedDownload openSubmitted(UUID attachmentId, UUID conversationId, UUID userMessageId) {
        var attachment = owned(attachmentId);
        verifySubmission(attachment, conversationId, userMessageId);
        requireAvailable(attachment);
        var ref = attachment.getFileRef();
        return new SubmittedDownload(storages.getByName(ref.getStorageName()).openStream(ref),
                attachment.getFileName(), attachment.getSizeBytes());
    }

    /** Called by the UI after native USER persistence and before AI processing. Never a model tool. */
    @Transactional
    public void bindToMessage(UUID attachmentId, UUID userMessageId) {
        var submitted=readOwned(attachmentId);
        var message=ownership.userMessage(userMessageId,submitted.getOriginalConversationId());
        // Serialise separate staged files binding to the same actual submission.
        data.load(AiChatMessageEntity.class).id(userMessageId).lockMode(LockModeType.PESSIMISTIC_WRITE).one();
        var attachment=owned(attachmentId);
        if(attachment.getSourceMessageId()!=null && !attachment.getSourceMessageId().equals(userMessageId))
            throw new SecurityException("Attachment is already bound to another submission");
        if(attachment.getFileDeletedAt()!=null || attachment.getRemovalRequestedAt()!=null)
            throw new IllegalStateException("File is unavailable");
        var others=forMessage(message);
        if(others.stream().anyMatch(e->!e.id().equals(attachmentId)))
            throw new IllegalStateException("Only one attachment per submission is supported");
        attachment.setSourceMessageId(userMessageId);data.saveWithoutReload(attachment);
    }
    @Transactional
    public List<SubmittedAttachment> forMessage(UUID conversationId,UUID userMessageId) {
        return forMessage(ownership.userMessage(userMessageId,conversationId));
    }
    private List<SubmittedAttachment> forMessage(AiChatMessageEntity message) {
        return data.load(AiScheduleAttachment.class)
                .query("select e from AiScheduleAttachment e where e.originalConversationId=:conversation and e.sourceMessageId=:message and e.owner=:owner")
                .parameter("conversation",message.getConversation().getId()).parameter("message",message.getId())
                .parameter("owner",ownership.username()).list().stream()
                .map(attachment->metadata(attachment,message.getCreatedDate())).toList();
    }
    /** Metadata only: unavailable files retain a truthful sent-file receipt without exposing FileRef. */
    @Transactional
    public SubmittedAttachment boundMetadata(UUID attachmentId,UUID conversationId,UUID userMessageId) {
        var attachment=readOwned(attachmentId);
        var message = verifySubmission(attachment, conversationId, userMessageId);
        return metadata(attachment, message.getCreatedDate());
    }

    void verifySubmission(UUID attachmentId, UUID conversationId, UUID sourceMessageId) {
        verifySubmission(readOwned(attachmentId), conversationId, sourceMessageId);
    }

    private AiChatMessageEntity verifySubmission(AiScheduleAttachment attachment, UUID conversationId, UUID userMessageId) {
        var message = ownership.userMessage(userMessageId, conversationId);
        if (!conversationId.equals(attachment.getOriginalConversationId()) || !userMessageId.equals(attachment.getSourceMessageId())) {
            throw new SecurityException("Attachment is unavailable for this submission");
        }
        return message;
    }

    private SubmittedAttachment metadata(AiScheduleAttachment attachment,OffsetDateTime submittedAt) {
        return new SubmittedAttachment(attachment.getId(),attachment.getFileName(),attachment.getSizeBytes(),
                available(attachment),attachment.getSourceMessageId(),submittedAt);
    }
    @Transactional(readOnly = true)
    public String boundFileName(UUID attachmentId,UUID conversationId,UUID userMessageId) {
        var attachment = readOwned(attachmentId);
        verifySubmission(attachment, conversationId, userMessageId);
        return attachment.getFileName();
    }
    private boolean available(AiScheduleAttachment attachment) {
        if (attachment.getFileDeletedAt()!=null || attachment.getRemovalRequestedAt()!=null) return false;
        var ref=attachment.getFileRef();
        try {
            return storages.getAll().stream().filter(storage->storage.getStorageName().equals(ref.getStorageName()))
                    .findFirst().map(storage->storage.fileExists(ref)).orElse(false);
        } catch (FileStorageException e) {
            return false;
        }
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
    AiScheduleAttachment reference(UUID id,UUID conversationId) {
        var attachment=owned(id);
        if(!attachment.getOriginalConversationId().equals(conversationId)) throw new SecurityException("Attachment belongs to another conversation");
        return attachment;
    }
    AiChatMessageEntity submittedSource(AiScheduleAttachment attachment,UUID conversationId,UUID requestMessageId) {
        var source=ownership.userMessage(attachment.getSourceMessageId(),conversationId);
        var request=ownership.userMessage(requestMessageId,conversationId);
        if (!ScheduleChatContext.atOrBefore(source,request)) throw new SecurityException("Attachment is unavailable for this submission");
        return source;
    }
    void requireAvailable(AiScheduleAttachment attachment) {
        if (!available(attachment)) throw new IllegalStateException("File is unavailable");
    }
    private AiScheduleAttachment readOwned(UUID id) {
        var attachment=data.load(AiScheduleAttachment.class).id(id).optional()
                .orElseThrow(()->new SecurityException("Attachment is unavailable"));
        ownership.check(attachment.getOwner(),attachment.getOriginalConversationId());
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
        return data.load(AiScheduleAttachment.class).id(id).lockMode(LockModeType.PESSIMISTIC_WRITE).optional()
                .orElseThrow(()->new SecurityException("Attachment is unavailable"));
    }
    List<AiScheduleImport> references(UUID id) {
        return data.load(AiScheduleImport.class).query("select e from AiScheduleImport e where e.attachment.id = :id").parameter("id",id).list();
    }
    void deleteFile(AiScheduleAttachment attachment) {
        var storage=storages.getByName(attachment.getFileRef().getStorageName());
        storage.removeFile(attachment.getFileRef());attachment.setFileDeletedAt(OffsetDateTime.now());
    }
}
