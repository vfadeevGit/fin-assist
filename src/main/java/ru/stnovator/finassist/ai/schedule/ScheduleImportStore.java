package ru.stnovator.finassist.ai.schedule;

import tools.jackson.databind.json.JsonMapper;
import io.jmix.aitoolsflowuidata.entity.AiChatMessageEntity;
import java.math.BigDecimal;
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
    public enum Status { COLLECTING, PREVIEW_READY, SAVED, CANCELLED, EXPIRED, NO_CHANGES }
    public record DraftContent(Kind kind, UUID source, UUID sourceMessageId, ScheduleTableTypes.Mapping mapping,
                               List<Row> rows, UUID contractId, UUID projectId, Mode mode,
                               String addendumNumber, LocalDate effectiveDate, String fingerprint,
                               UUID requestMessageId,UUID startedByMessageId) {
        public DraftContent { rows = rows == null ? List.of() : List.copyOf(rows); }
        public DraftContent(Kind kind,UUID source,UUID sourceMessageId,ScheduleTableTypes.Mapping mapping,
                            List<Row> rows,UUID contractId,UUID projectId,Mode mode,String addendumNumber,
                            LocalDate effectiveDate,String fingerprint) {
            this(kind,source,sourceMessageId,mapping,rows,contractId,projectId,mode,addendumNumber,effectiveDate,fingerprint,null,null);
        }
        UUID requestOrSource() {
            return requestMessageId==null?sourceMessageId:requestMessageId;
        }
        private UUID startedBy() {
            return startedByMessageId==null?requestOrSource():startedByMessageId;
        }
    }
    public record Draft(UUID operationId, UUID conversationId, long revision, Status status,
                        OffsetDateTime createdAt, OffsetDateTime touchedAt, OffsetDateTime terminalAt,
                        UUID resultId, DraftContent content) {}
    public enum AttachmentStage { ATTACHED, NEEDS_SHEET, NEEDS_COLUMNS, PARSED, ERROR }
    public record AttachmentProcessing(AttachmentStage stage, UUID operationId, UUID requestMessageId,
                                       OffsetDateTime requestedAt, Kind kind, int rowCount, BigDecimal total,
                                       String issueCode) {}
    private record AttachmentObservation(AttachmentStage stage, String issueCode) {}
    private record Envelope(int schemaVersion, DraftContent content, AttachmentObservation attachmentObservation) {}
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
    public record ReceiptDestination(String entityName, UUID resultId) {}

    /** A committed native assistant receipt uses its operation ID as its message ID. */
    @Transactional(readOnly = true)
    public Optional<ReceiptDestination> receiptForMessage(UUID conversationId, UUID assistantMessageId) {
        ownership.conversation(conversationId);
        return data.load(AiScheduleImport.class)
                .query("e.id=:id and e.originalConversationId=:c and e.owner=:owner and e.status=:status")
                .parameter("id", assistantMessageId).parameter("c", conversationId).parameter("owner", ownership.username())
                .parameter("status", Status.SAVED.name()).optional().map(this::draft)
                .map(saved -> new ReceiptDestination(ScheduleImportCommitService.resultType(saved.content()).getSimpleName(), saved.resultId()));
    }

    public record ReceiptSnapshot(UUID operationId, OffsetDateTime savedAt, Mode mode, int rowCount, BigDecimal total) {}

    /** Saved before native receipt publication, or whose native receipt no longer exists. */
    @Transactional(readOnly = true)
    public List<ReceiptSnapshot> receiptsWithoutMessage(UUID conversationId) {
        ownership.conversation(conversationId);
        return data.load(AiScheduleImport.class).query("""
                select e from AiScheduleImport e
                where e.originalConversationId = :conversation and e.owner = :owner and e.status = :status
                  and not exists (select m.id from aitls_AiChatMessageEntity m
                                  where m.id = e.id and m.conversation.id = :conversation)
                order by e.terminalAt, e.id
                """).parameter("conversation", conversationId).parameter("owner", ownership.username())
                .parameter("status", Status.SAVED.name()).list().stream().map(this::draft)
                .map(receipt -> {
                    var content = receipt.content();
                    var total = content.rows().stream().map(Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                    return new ReceiptSnapshot(receipt.operationId(),
                            Objects.requireNonNullElse(receipt.terminalAt(), receipt.createdAt()),
                            content.mode(), content.rows().size(), total);
                }).toList();
    }

    /** Resolve the newest request that selected this exact submitted file. */
    @Transactional(readOnly = true)
    public AttachmentProcessing attachmentProcessing(UUID conversationId, UUID attachmentId, UUID sourceMessageId) {
        attachments.verifySubmission(attachmentId, conversationId, sourceMessageId);
        return data.load(AiScheduleImport.class)
                .query("select e from AiScheduleImport e where e.originalConversationId=:conversation"
                        + " and e.owner=:owner and e.attachment.id=:attachment order by e.createdDate desc, e.id desc")
                .parameter("conversation", conversationId).parameter("owner", ownership.username())
                .parameter("attachment", attachmentId).list().stream()
                .map(entity -> processing(entity, sourceMessageId))
                .flatMap(Optional::stream)
                // The query order breaks ties between two operations started from the same USER message.
                .min(Comparator.comparing((AttachmentProcessing p) -> p.requestedAt().toInstant()).reversed()
                        .thenComparing(p -> p.requestMessageId().toString(), Comparator.reverseOrder()))
                .orElseGet(() -> new AttachmentProcessing(AttachmentStage.ATTACHED, null, null, null,
                        null, 0, null, null));
    }

    private Optional<AttachmentProcessing> processing(AiScheduleImport entity, UUID sourceMessageId) {
        var envelope = envelope(entity.getContentJson());
        var content = envelope.content();
        if (content == null || !sourceMessageId.equals(content.sourceMessageId())) return Optional.empty();
        var request = ownership.userMessage(content.requestOrSource(), entity.getOriginalConversationId());
        var observation = envelope.attachmentObservation();
        var stage = observation != null ? observation.stage()
                : content.mapping() != null && !content.rows().isEmpty() ? AttachmentStage.PARSED : AttachmentStage.ATTACHED;
        var rows = stage == AttachmentStage.PARSED ? content.rows() : List.<Row>of();
        var total = rows.isEmpty() ? null : rows.stream().map(Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(new AttachmentProcessing(stage, entity.getId(), request.getId(), request.getCreatedDate(),
                content.kind(), rows.size(), total, observation == null ? null : observation.issueCode()));
    }

    /** Processing observations never change the draft revision or authorize a proposal. */
    @Transactional
    void recordAttachmentObservation(UUID operationId, long revision, AttachmentStage stage, String issueCode) {
        var entity = editable(operationId, revision);
        var envelope = envelope(entity.getContentJson());
        var content = envelope.content();
        if (content.source() == null || content.sourceMessageId() == null) return;
        var observation = stage == AttachmentStage.PARSED ? null : new AttachmentObservation(stage, issueCode);
        if (Objects.equals(envelope.attachmentObservation(), observation)) return;
        entity.setContentJson(encode(content, observation));
        data.saveWithoutReload(entity);
    }

    /** Resolve a retry by the initial request, even after a later message selects a different source. */
    @Transactional(readOnly = true)
    Optional<UUID> findStartedBy(UUID conversationId,UUID userMessageId,Kind kind) {
        ownership.userMessage(userMessageId,conversationId);
        return data.load(AiScheduleImport.class)
                .query("select e from AiScheduleImport e where e.originalConversationId=:conversation and e.owner=:owner order by e.createdDate, e.id")
                .parameter("conversation",conversationId).parameter("owner",ownership.username()).list().stream()
                .filter(entity->{
                    var content=decode(entity.getContentJson());
                    return content!=null && content.kind()==kind && userMessageId.equals(content.startedBy());
                }).map(AiScheduleImport::getId).findFirst();
    }
    @Transactional
    public Draft update(UUID operationId, long expectedRevision, DraftContent content) {
        Objects.requireNonNull(content,"content");
        var entity=editable(operationId,expectedRevision);
        var prior=decode(entity.getContentJson());
        UUID request=prior!=null && prior.requestOrSource()!=null?prior.requestOrSource():content.sourceMessageId();
        UUID conversationId=entity.getOriginalConversationId();
        if (content.sourceMessageId()!=null) ownership.userMessage(content.sourceMessageId(),conversationId);
        if (request!=null) ownership.userMessage(request,conversationId);
        var attachment=content.source()==null?null:attachments.reference(content.source(),conversationId);
        if (attachment!=null) {
            // Historical staged-only internal drafts are readable. New chat drafts must be bound to a USER.
            if (!Objects.equals(attachment.getSourceMessageId(),content.sourceMessageId())
                    || (attachment.getSourceMessageId()==null && request!=null))
                throw new SecurityException("Attachment is unavailable for this submission");
            if (request!=null) attachments.submittedSource(attachment,conversationId,request);
            boolean usesRecognizedRows = prior != null && prior.mapping() != null && !prior.rows().isEmpty()
                    && Objects.equals(prior.source(), content.source()) && Objects.equals(prior.mapping(), content.mapping())
                    && prior.rows().equals(content.rows());
            if (!usesRecognizedRows) attachments.requireAvailable(attachment);
        }
        entity.setAttachment(attachment);
        return updateContent(entity,content,request,prior);
    }
    /** Select exactly one submitted source and discard all data derived from the previous file. */
    @Transactional
    public Draft selectSource(UUID operationId,long expectedRevision,UUID attachmentId,UUID actualUserMessageId) {
        var entity=editable(operationId,expectedRevision);
        var current=decode(entity.getContentJson());
        if (current==null) throw new IllegalStateException("Draft content is required");
        UUID conversationId=entity.getOriginalConversationId();
        var request=ownership.userMessage(actualUserMessageId,conversationId);
        requireVisible(current.requestOrSource(),conversationId,request);
        if (!Objects.equals(current.requestOrSource(),current.sourceMessageId()))
            requireVisible(current.sourceMessageId(),conversationId,request);
        var attachment=attachments.reference(attachmentId,conversationId);
        var source=attachments.submittedSource(attachment,conversationId,actualUserMessageId);
        attachments.requireAvailable(attachment);
        if (attachmentId.equals(current.source()) && actualUserMessageId.equals(current.requestOrSource())) return draft(entity);
        entity.setAttachment(attachment);
        return updateContent(entity,new DraftContent(current.kind(),attachmentId,source.getId(),null,List.of(),
                current.contractId(),current.projectId(),null,null,null,null),actualUserMessageId,current);
    }
    private void requireVisible(UUID messageId,UUID conversationId,AiChatMessageEntity request) {
        if (messageId!=null && !ScheduleChatContext.atOrBefore(ownership.userMessage(messageId,conversationId),request))
            throw new SecurityException("Import is unavailable for this submission");
    }
    private Draft updateContent(AiScheduleImport entity,DraftContent content,UUID request,DraftContent prior) {
        UUID startedBy=prior==null || prior.startedBy()==null?request:prior.startedBy();
        var invalidated=new DraftContent(content.kind(),content.source(),content.sourceMessageId(),content.mapping(),content.rows(),
                content.contractId(),content.projectId(),content.mode(),content.addendumNumber(),content.effectiveDate(),null,request,startedBy);
        var observation = prior != null && Objects.equals(prior.source(), content.source())
                && Objects.equals(prior.requestOrSource(), request)
                ? envelope(entity.getContentJson()).attachmentObservation() : null;
        entity.setContentJson(encode(invalidated, observation)); entity.setStatus(Status.COLLECTING.name());
        advance(entity);
        return draft(data.save(entity));
    }
    @Transactional
    public void cancel(UUID operationId,long expectedRevision) {
        var entity=editable(operationId,expectedRevision);finish(entity,Status.CANCELLED);data.saveWithoutReload(entity);
    }
    @Transactional
    public void finishUnchanged(UUID operationId, long revision) {
        var entity = editable(operationId, revision);
        finish(entity, Status.NO_CHANGES);
        data.saveWithoutReload(entity);
    }
    /** Trusted workflow boundary. A client cannot set status, revision, or fingerprint through update(). */
    @Transactional
    Draft markPreviewReady(UUID operationId,long expectedRevision,String fingerprint) {
        if (fingerprint==null || fingerprint.isBlank()) throw new IllegalArgumentException("Fingerprint is required");
        var entity=editable(operationId,expectedRevision);var c=decode(entity.getContentJson());
        if(c==null) throw new IllegalStateException("Draft content is required");
        entity.setContentJson(encode(new DraftContent(c.kind(),c.source(),c.sourceMessageId(),c.mapping(),c.rows(),
                c.contractId(),c.projectId(),c.mode(),c.addendumNumber(),c.effectiveDate(),fingerprint,c.requestOrSource(),c.startedBy()),
                envelope(entity.getContentJson()).attachmentObservation()));
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
    private String encode(DraftContent content, AttachmentObservation observation) {
        return json.writeValueAsString(new Envelope(1, content, observation));
    }
    private DraftContent decode(String value) { return envelope(value).content(); }
    private Envelope envelope(String value) {
        var envelope=json.readValue(value,Envelope.class);
        if(envelope.schemaVersion()!=1) throw new IllegalStateException("Unsupported draft schema version");
        return envelope;
    }
}
