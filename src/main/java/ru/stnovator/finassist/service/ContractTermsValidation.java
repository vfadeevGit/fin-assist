package ru.stnovator.finassist.service;

import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.event.EntityChangedEvent;
import io.jmix.core.event.EntitySavingEvent;
import io.jmix.core.security.SystemAuthenticator;
import jakarta.persistence.LockModeType;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.stnovator.finassist.entity.Addendum;
import ru.stnovator.finassist.entity.Contract;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Locks serialize all term mutations; final validation sees the complete transaction, including removals. */
@Component
public class ContractTermsValidation {
    private final DataManager data;
    private final SystemAuthenticator authenticator;
    private final ContractTermsService terms;
    private final RelatedDataAccess access;

    public ContractTermsValidation(DataManager data, SystemAuthenticator authenticator, ContractTermsService terms,
                                   RelatedDataAccess access) {
        this.data = data;
        this.authenticator = authenticator;
        this.terms = terms;
        this.access = access;
    }

    @EventListener
    public void savingAddendum(EntitySavingEvent<Addendum> event) {
        Addendum draft = event.getEntity();
        if (draft.getContract() == null) return; // Required-attribute validation handles incomplete entities.
        authenticator.runWithSystem(() -> {
            lock(draft.getContract().getId());
            if (!event.isNewEntity()) {
                data.load(Addendum.class).id(draft.getId())
                        .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL).add("contract", FetchPlan.LOCAL))
                        .optional().ifPresent(old -> {
                            if (!old.getContract().getId().equals(draft.getContract().getId())) {
                                throw terms.failure("termsContractImmutable");
                            }
                        });
            }
            // Validate precision before the database rounds a decimal(19,2) input.
            if (draft.getAmountAdjustment() != null
                    && (draft.getAmountAdjustment().stripTrailingZeros().scale() > 2
                    || draft.getAmountAdjustment().abs().precision() - draft.getAmountAdjustment().scale() > 17)) {
                throw terms.failure("termsAmountPrecision");
            }
        });
    }

    @EventListener
    public void savingContract(EntitySavingEvent<Contract> event) {
        if (!event.isNewEntity()) authenticator.runWithSystem(() -> lock(event.getEntity().getId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void changedAddendum(EntityChangedEvent<Addendum> event) {
        validateAsSystem(() -> {
            UUID contractId;
            if (event.getType() == EntityChangedEvent.Type.DELETED) {
                Id<Contract> oldContract = event.getChanges().getOldReferenceId("contract");
                contractId = oldContract == null ? null : (UUID) oldContract.getValue();
            } else {
                contractId = data.load(Addendum.class).id(event.getEntityId().getValue())
                        .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL).add("contract", FetchPlan.LOCAL))
                        .optional().map(a -> a.getContract().getId()).orElse(null);
            }
            if (contractId != null) validate(contractId);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void changedContract(EntityChangedEvent<Contract> event) {
        if (event.getType() != EntityChangedEvent.Type.DELETED) {
            validateAsSystem(() -> validate((UUID) event.getEntityId().getValue()));
        }
    }

    private void validateAsSystem(Runnable validation) {
        try {
            authenticator.runWithSystem(validation);
        } catch (ContractTermsException e) {
            // Authentication has been restored here. Do not disclose identifiers from hidden history.
            if (e.getAddendumId() != null && (!access.canRead(Addendum.class, "number")
                    || data.load(Addendum.class).id(e.getAddendumId()).fetchPlan(FetchPlan.LOCAL).optional().isEmpty())) {
                throw terms.failure("termsInvalidHiddenHistory");
            }
            throw e;
        }
    }

    private void lock(UUID contractId) {
        data.load(Contract.class).id(contractId).fetchPlan(FetchPlan.LOCAL)
                .lockMode(LockModeType.PESSIMISTIC_WRITE).optional();
    }

    private void validate(UUID contractId) {
        lock(contractId);
        data.load(Contract.class).id(contractId).fetchPlan(FetchPlan.LOCAL).optional().ifPresent(contract ->
                terms.evaluate(contract, terms.loadAddenda(List.of(contractId), LocalDate.of(9999, 12, 31))));
    }
}
