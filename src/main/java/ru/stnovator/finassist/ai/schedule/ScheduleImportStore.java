package ru.stnovator.finassist.ai.schedule;

import tools.jackson.databind.json.JsonMapper;
import io.jmix.core.UnconstrainedDataManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.stnovator.finassist.entity.AiScheduleImport;
import java.time.*;
import java.util.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

@Service
public class ScheduleImportStore {
    public enum Status { COLLECTING, PREVIEW_READY, SAVED, CANCELLED, EXPIRED }
    public record DraftContent(Kind kind, UUID source, UUID sourceMessageId, ScheduleTableTypes.Mapping mapping,
                               List<Row> rows, UUID contractId, UUID projectId, Mode mode,
                               String addendumNumber, LocalDate effectiveDate, String fingerprint) {
        public DraftContent { rows = rows == null ? List.of() : List.copyOf(rows); }
    }
    public record Draft(UUID operationId, UUID conversationId, long revision, Status status,
                        OffsetDateTime createdAt, OffsetDateTime touchedAt, OffsetDateTime terminalAt,
                        UUID resultId, DraftContent content) {}
    private record Envelope(int schemaVersion, DraftContent content) {}
    private final UnconstrainedDataManager data;
    private final ScheduleImportOwnership ownership;
    private final ScheduleAttachmentService attachments;
    private final ScheduleImportProperties properties;
    private final JsonMapper json = JsonMapper.builder().build();
    public ScheduleImportStore(UnconstrainedDataManager data, ScheduleImportOwnership ownership,
                               ScheduleAttachmentService attachments, ScheduleImportProperties properties) {
        this.data=data; this.ownership=ownership; this.attachments=attachments; this.properties=properties;
    }
    @Transactional
    public UUID create(UUID conversationId) {
        var conversation=ownership.conversation(conversationId);
        var entity=data.create(AiScheduleImport.class);
        entity.setOwner(ownership.username()); entity.setConversation(conversation); entity.setOriginalConversationId(conversationId);
        return data.save(entity).getId();
    }
    @Transactional
    public Draft load(UUID operationId) {
        var entity=owned(operationId);
        if (active(entity) && entity.getTouchedAt().plus(properties.getDraftTtl()).isBefore(OffsetDateTime.now())) {
            finish(entity, Status.EXPIRED); entity=data.save(entity);
        }
        return draft(entity);
    }
    /** Owned durable receipts, independent of chat history and UI session state. */
    @Transactional(readOnly = true)
    public List<Draft> savedForConversation(UUID conversationId) {
        ownership.conversation(conversationId);
        return data.load(AiScheduleImport.class)
                .query("select e from AiScheduleImport e where e.originalConversationId=:conversation and e.owner=:owner and e.status=:status order by e.terminalAt desc, e.id")
                .parameter("conversation", conversationId).parameter("owner", ownership.username())
                .parameter("status", Status.SAVED.name()).list().stream().map(this::draft).toList();
    }
    @Transactional
    public Draft update(UUID operationId, long expectedRevision, DraftContent content) {
        Objects.requireNonNull(content,"content");
        var entity=editable(operationId,expectedRevision);
        if(content.sourceMessageId()!=null)ownership.userMessage(content.sourceMessageId(),entity.getOriginalConversationId());
        entity.setAttachment(content.source()==null ? null : attachments.reference(content.source(),entity.getOriginalConversationId(),operationId));
        var invalidated=new DraftContent(content.kind(),content.source(),content.sourceMessageId(),content.mapping(),content.rows(),
                content.contractId(),content.projectId(),content.mode(),content.addendumNumber(),content.effectiveDate(),null);
        entity.setContentJson(encode(invalidated)); entity.setStatus(Status.COLLECTING.name());
        advance(entity);
        return draft(data.save(entity));
    }
    @Transactional
    public void cancel(UUID operationId,long expectedRevision) {
        var entity=editable(operationId,expectedRevision);finish(entity,Status.CANCELLED);data.saveWithoutReload(entity);
    }
    /** Trusted workflow boundary. A client cannot set status, revision, or fingerprint through update(). */
    @Transactional
    Draft markPreviewReady(UUID operationId,long expectedRevision,String fingerprint) {
        if (fingerprint==null || fingerprint.isBlank()) throw new IllegalArgumentException("Fingerprint is required");
        var entity=editable(operationId,expectedRevision);var c=decode(entity.getContentJson());
        if(c==null) throw new IllegalStateException("Draft content is required");
        entity.setContentJson(encode(new DraftContent(c.kind(),c.source(),c.sourceMessageId(),c.mapping(),c.rows(),
                c.contractId(),c.projectId(),c.mode(),c.addendumNumber(),c.effectiveDate(),fingerprint)));
        entity.setStatus(Status.PREVIEW_READY.name());advance(entity);return draft(data.save(entity));
    }
    /** Task8 uses this in its own transaction, then saves SAVED/resultId in that same domain transaction. */
    AiScheduleImport lockForCommit(UUID operationId,long expectedRevision) {
        var entity=editable(operationId,expectedRevision);
        if (!Status.PREVIEW_READY.name().equals(entity.getStatus())) throw new IllegalStateException("Preview is not ready");
        return entity;
    }
    private AiScheduleImport owned(UUID id) {
        var entity=data.load(AiScheduleImport.class).id(id).lockMode(LockModeType.PESSIMISTIC_WRITE).optional()
                .orElseThrow(()->new SecurityException("Import is unavailable"));
        ownership.receipt(entity.getOwner(),entity.getOriginalConversationId());return entity;
    }
    private AiScheduleImport editable(UUID id,long revision) {
        var entity=owned(id);
        if(entity.getConversation()==null) throw new IllegalStateException("Conversation has been removed");
        if(entity.getRevision()!=revision) throw new ConcurrentModificationException("Draft revision has changed");
        if(!active(entity) || entity.getTouchedAt().plus(properties.getDraftTtl()).isBefore(OffsetDateTime.now()))
            throw new IllegalStateException("Import is closed or expired");
        return entity;
    }
    static boolean active(AiScheduleImport entity) {
        return Status.COLLECTING.name().equals(entity.getStatus()) || Status.PREVIEW_READY.name().equals(entity.getStatus());
    }
    private static void advance(AiScheduleImport entity) { entity.setRevision(entity.getRevision()+1);entity.setTouchedAt(OffsetDateTime.now()); }
    static void finish(AiScheduleImport entity,Status status) {
        advance(entity);entity.setStatus(status.name());entity.setTerminalAt(OffsetDateTime.now());
    }
    Draft draft(AiScheduleImport e) {
        return new Draft(e.getId(),e.getOriginalConversationId(),e.getRevision(),Status.valueOf(e.getStatus()),
                e.getCreatedDate(),e.getTouchedAt(),e.getTerminalAt(),e.getResultId(),decode(e.getContentJson()));
    }
    private String encode(DraftContent content) {return json.writeValueAsString(new Envelope(1,content));}
    private DraftContent decode(String value) {
        var envelope=json.readValue(value,Envelope.class);
        if(envelope.schemaVersion()!=1) throw new IllegalStateException("Unsupported draft schema version");
        return envelope.content();
    }
}
