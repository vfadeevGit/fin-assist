package ru.stnovator.finassist.entity;

import io.jmix.aitoolsflowuidata.entity.AiConversationEntity;
import io.jmix.core.FileRef;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
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
@Table(name = "AI_SCHEDULE_ATTACHMENT", indexes = {
        @Index(name = "IDX_AI_SCHEDULE_ATTACHMENT_CONVERSATION", columnList = "CONVERSATION_ID")
})
@Entity
public class AiScheduleAttachment {
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

    @JoinColumn(name = "CONVERSATION_ID", nullable = false, updatable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @NotNull
    private AiConversationEntity conversation;

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

    @Column(name = "FILE_NAME", nullable = false)
    private String fileName;

    @Column(name = "FILE_REF", nullable = false, length = 1024)
    private FileRef fileRef;

    @Column(name = "SIZE_BYTES", nullable = false)
    private Long sizeBytes = 0L;

    @Column(name = "STAGED_AT", nullable = false)
    private OffsetDateTime stagedAt = OffsetDateTime.now();

    @Column(name = "REMOVAL_REQUESTED_AT")
    private OffsetDateTime removalRequestedAt;

    @Column(name = "FILE_DELETED_AT")
    private OffsetDateTime fileDeletedAt;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { version = value; }
    public String getOwner() { return owner; }
    public void setOwner(String value) { owner = value; }
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
    public String getFileName() { return fileName; }
    public void setFileName(String value) { fileName = value; }
    public FileRef getFileRef() { return fileRef; }
    public void setFileRef(FileRef value) { fileRef = value; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long value) { sizeBytes = value; }
    public OffsetDateTime getStagedAt() { return stagedAt; }
    public void setStagedAt(OffsetDateTime value) { stagedAt = value; }
    public OffsetDateTime getRemovalRequestedAt() { return removalRequestedAt; }
    public void setRemovalRequestedAt(OffsetDateTime value) { removalRequestedAt = value; }
    public OffsetDateTime getFileDeletedAt() { return fileDeletedAt; }
    public void setFileDeletedAt(OffsetDateTime value) { fileDeletedAt = value; }
}
