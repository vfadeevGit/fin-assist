package ru.stnovator.finassist.service;

import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.event.EntityChangedEvent;
import io.jmix.core.event.EntitySavingEvent;
import io.jmix.core.security.SystemAuthenticator;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.stnovator.finassist.entity.Addendum;
import ru.stnovator.finassist.entity.Contract;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.TreeSet;

/** Locks serialize all term mutations; final validation sees the complete transaction, including removals. */
@Component
public class ContractTermsValidation {
    private final DataManager data;
    private final SystemAuthenticator authenticator;
    private final ContractTermsService terms;
    private final RelatedDataAccess access;
    private final ScheduleMutationGuard mutations;

    public ContractTermsValidation(DataManager data, SystemAuthenticator authenticator, ContractTermsService terms,
                                   RelatedDataAccess access, ScheduleMutationGuard mutations) {
        this.data = data;
        this.authenticator = authenticator;
        this.terms = terms;
        this.access = access;
        this.mutations = mutations;
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

    @EventListener
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
            if (contractId != null) validateBeforeCommit(contractId);
        });
    }

    @EventListener
    public void changedContract(EntityChangedEvent<Contract> event) {
        if (event.getType() != EntityChangedEvent.Type.DELETED) {
            validateBeforeCommit((UUID) event.getEntityId().getValue());
        }
    }

    private void validateBeforeCommit(UUID contractId) {
        lock(contractId);
        // A normal synchronous event listener acquires the lock now. Validation is deferred so
        // several DataManager saves/removes in one outer transaction are evaluated as a whole.
        FinalValidation pending = TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(FinalValidation.class::isInstance).map(FinalValidation.class::cast).findFirst()
                .orElseGet(() -> {
                    var synchronization = new FinalValidation();
                    TransactionSynchronizationManager.registerSynchronization(synchronization);
                    return synchronization;
                });
        pending.contractIds.add(contractId);
    }

    private final class FinalValidation implements TransactionSynchronization {
        private final Set<UUID> contractIds = new TreeSet<>();

        @Override
        public void beforeCommit(boolean readOnly) {
            contractIds.forEach(id -> validateAsSystem(() -> validate(id)));
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
        mutations.lockContracts(Set.of(contractId));
    }

    private void validate(UUID contractId) {
        lock(contractId);
        data.load(Contract.class).id(contractId).fetchPlan(FetchPlan.LOCAL).optional().ifPresent(contract ->
                terms.evaluate(contract, terms.loadAddenda(List.of(contractId), LocalDate.of(9999, 12, 31))));
    }
}
