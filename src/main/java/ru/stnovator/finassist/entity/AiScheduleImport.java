package ru.stnovator.finassist.entity;

import io.jmix.aitoolsflowuidata.entity.AiConversationEntity;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.entity.annotation.OnDeleteInverse;
import io.jmix.core.DeletePolicy;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Technical import state. Writes are restricted to server services. */
@JmixEntity
@Table(name = "AI_SCHEDULE_IMPORT", indexes = {
        @Index(name = "IDX_AI_SCHEDULE_IMPORT_ORIGINAL_CONV", columnList = "ORIGINAL_CONVERSATION_ID"),
        @Index(name = "IDX_AI_SCHEDULE_IMPORT_CONVERSATION", columnList = "CONVERSATION_ID"),
        @Index(name = "IDX_AI_SCHEDULE_IMPORT_ATTACHMENT", columnList = "ATTACHMENT_ID")
})
@Entity
public class AiScheduleImport {
    @JmixGeneratedValue
    @Id
    @Column(name = "ID", nullable = false)
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @Column(name = "OWNER", nullable = false, updatable = false)
    @NotNull
    private String owner;

    @OnDeleteInverse(DeletePolicy.UNLINK)
    @JoinColumn(name = "CONVERSATION_ID", updatable = false)
    @ManyToOne(fetch = FetchType.LAZY)
    private AiConversationEntity conversation;

    /** Immutable provenance survives hard deletion of the live chat conversation. */
    @NotNull
    @Column(name = "ORIGINAL_CONVERSATION_ID", nullable = false, updatable = false)
    private UUID originalConversationId;

    @CreatedBy
    @Column(name = "CREATED_BY")
    private String createdBy;

    @CreatedDate
    @Column(name = "CREATED_DATE")
    private OffsetDateTime createdDate;

    @LastModifiedBy
    @Column(name = "LAST_MODIFIED_BY")
    private String lastModifiedBy;

    @LastModifiedDate
    @Column(name = "LAST_MODIFIED_DATE")
    private OffsetDateTime lastModifiedDate;

    @Column(name = "REVISION", nullable = false)
    private Long revision = 0L;

    @Column(name = "IMPORT_STATUS", nullable = false, length = 30)
    private String status = "COLLECTING";

    @Column(name = "CONTENT_JSON", nullable = false, columnDefinition = "text")
    private String contentJson = "{\"schemaVersion\":1,\"content\":null}";

    @Column(name = "TOUCHED_AT", nullable = false)
    private OffsetDateTime touchedAt = OffsetDateTime.now();

    @Column(name = "TERMINAL_AT")
    private OffsetDateTime terminalAt;

    @Column(name = "RESULT_ID")
    private UUID resultId;

    @JoinColumn(name = "ATTACHMENT_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private AiScheduleAttachment attachment;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { version = value; }
    public String getOwner() { return owner; }
    public void setOwner(String value) { owner = value; }
    public UUID getOriginalConversationId() { return originalConversationId; }
    public void setOriginalConversationId(UUID value) { originalConversationId = value; }
    public AiConversationEntity getConversation() { return conversation; }
    public void setConversation(AiConversationEntity value) { conversation = value; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String value) { createdBy = value; }
    public OffsetDateTime getCreatedDate() { return createdDate; }
    public void setCreatedDate(OffsetDateTime value) { createdDate = value; }
    public String getLastModifiedBy() { return lastModifiedBy; }
    public void setLastModifiedBy(String value) { lastModifiedBy = value; }
    public OffsetDateTime getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(OffsetDateTime value) { lastModifiedDate = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String value) { contentJson = value; }
    public OffsetDateTime getTouchedAt() { return touchedAt; }
    public void setTouchedAt(OffsetDateTime value) { touchedAt = value; }
    public OffsetDateTime getTerminalAt() { return terminalAt; }
    public void setTerminalAt(OffsetDateTime value) { terminalAt = value; }
    public UUID getResultId() { return resultId; }
    public void setResultId(UUID value) { resultId = value; }
    public AiScheduleAttachment getAttachment() { return attachment; }
    public void setAttachment(AiScheduleAttachment value) { attachment = value; }
}
