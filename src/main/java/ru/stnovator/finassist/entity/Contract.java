package ru.stnovator.finassist.entity;

import io.jmix.core.DeletePolicy;
import io.jmix.core.MetadataTools;
import io.jmix.core.annotation.DeletedBy;
import io.jmix.core.annotation.DeletedDate;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.entity.annotation.OnDelete;
import io.jmix.core.metamodel.annotation.*;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@JmixEntity
@Table(name = "CONTRACT", indexes = {
        @Index(name = "IDX_CONTRACT_CUSTOMER", columnList = "CUSTOMER_ID")
})
@Entity
public class Contract {
    @JmixGeneratedValue
    @Column(name = "ID", nullable = false)
    @Id
    private UUID id;

    @Column(name = "VERSION", nullable = false)
    @Version
    private Integer version;

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

    @DeletedBy
    @Column(name = "DELETED_BY")
    private String deletedBy;

    @DeletedDate
    @Column(name = "DELETED_DATE")
    private OffsetDateTime deletedDate;

    @JoinColumn(name = "CUSTOMER_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private Customer customer;

    @Column(name = "INTERNAL_ID", nullable = false, length = 50)
    @NotNull
    private String internalID;

    @Column(name = "INTERNAL_DATE", nullable = false)
    @NotNull
    private LocalDate internalDateBegin;

    @NotNull
    @Column(name = "INTERNAL_DATE_END", nullable = false)
    private LocalDate internalDateEnd;

    @Column(name = "PAYMENT_TYPE")
    private String paymentType;

    @Column(name = "SUM_TOTAL", precision = 19, scale = 2)
    private BigDecimal sumTotal;

    @Column(name = "DAYS_PAYMENT", precision = 6, scale = 0)
    private BigDecimal daysPayment;

    @OrderBy("name")
    @OnDelete(DeletePolicy.CASCADE)
    @Composition
    @OneToMany(mappedBy = "contract")
    private List<Project> projects;

    @NumberFormat(pattern = "", decimalSeparator = "6", groupingSeparator = "0")
    @JmixProperty
    @Transient
    private BigDecimal daysLongitude;

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public LocalDate getInternalDateEnd() {
        return internalDateEnd;
    }

    public void setInternalDateEnd(LocalDate internalDateEnd) {
        //TODO: Check that it is not earlier than begin date
        this.internalDateEnd = internalDateEnd;
    }

    public ContractType getPaymentType() {
        return paymentType == null ? null : ContractType.fromId(paymentType);
    }

    public void setPaymentType(ContractType paymentType) {
        this.paymentType = paymentType == null ? null : paymentType.getId();
    }

    public BigDecimal getDaysPayment() {
        return daysPayment;
    }

    public void setDaysPayment(BigDecimal daysPayment) {
        this.daysPayment = daysPayment;
    }

    public BigDecimal getSumTotal() {
        return sumTotal;
    }

    public void setSumTotal(BigDecimal sumTotal) {
        this.sumTotal = sumTotal;
    }

    public List<Project> getProjects() {
        return projects;
    }

    public void setProjects(List<Project> projects) {
        this.projects = projects;
    }

    public BigDecimal getDaysLongitude() {
        if (this.internalDateBegin == null || this.internalDateEnd == null) {
            return this.daysLongitude = BigDecimal.valueOf(0);
        } else {
            return this.daysLongitude = BigDecimal.valueOf(ChronoUnit.DAYS.between(this.internalDateBegin, this.internalDateEnd));
        }
    }

    public LocalDate getInternalDateBegin() {
        return internalDateBegin;
    }

    public void setInternalDateBegin(LocalDate internalDateBegin) {
        //TODO: Check that it is not later than end date
        this.internalDateBegin = internalDateBegin;
    }

    public String getInternalID() {
        return internalID;
    }

    public void setInternalID(String internalID) {
        this.internalID = internalID;
    }

    public OffsetDateTime getDeletedDate() {
        return deletedDate;
    }

    public void setDeletedDate(OffsetDateTime deletedDate) {
        this.deletedDate = deletedDate;
    }

    public String getDeletedBy() {
        return deletedBy;
    }

    public void setDeletedBy(String deletedBy) {
        this.deletedBy = deletedBy;
    }

    public OffsetDateTime getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(OffsetDateTime lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }

    public String getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public OffsetDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(OffsetDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    @InstanceName
    @DependsOnProperties({"customer", "internalID", "internalDateBegin"})
    public String getInstanceName(MetadataTools metadataTools, DatatypeFormatter datatypeFormatter) {
        return String.format("%s %s %s",
                metadataTools.format(customer),
                metadataTools.format(internalID),
                datatypeFormatter.formatLocalDate(internalDateBegin));
    }
}