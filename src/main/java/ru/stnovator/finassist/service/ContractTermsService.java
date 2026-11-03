package ru.stnovator.finassist.service;

import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Messages;
import io.jmix.core.security.SystemAuthenticator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import ru.stnovator.finassist.entity.Addendum;
import ru.stnovator.finassist.entity.Contract;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ContractTermsService {
    private static final int BATCH_SIZE = 128;
    private final DataManager data;
    private final RelatedDataAccess access;
    private final SystemAuthenticator authenticator;
    private final Messages messages;

    public ContractTermsService(DataManager data, RelatedDataAccess access,
                                SystemAuthenticator authenticator, Messages messages) {
        this.data = data;
        this.access = access;
        this.authenticator = authenticator;
        this.messages = messages;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public ContractTerms calculate(UUID contractId, LocalDate asOfDate) {
        return calculateAll(List.of(contractId), asOfDate).get(contractId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Map<UUID, ContractTerms> calculateAll(Collection<UUID> contractIds, LocalDate asOfDate) {
        Objects.requireNonNull(asOfDate, "asOfDate");
        requireReadAccess();
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(contractIds));
        Map<UUID, ContractTerms> result = new HashMap<>();
        for (int offset = 0; offset < ids.size(); offset += BATCH_SIZE) {
            List<UUID> batch = ids.subList(offset, Math.min(offset + BATCH_SIZE, ids.size()));
            List<Contract> contracts = data.load(Contract.class).ids(batch).fetchPlan(FetchPlan.LOCAL).list();
            if (contracts.size() != batch.size()) throw failure("termsUnavailable");
            List<Addendum> addenda = loadAddenda(batch, asOfDate);
            requireCompleteHistory(batch, asOfDate, addenda);
            Map<UUID, List<Addendum>> grouped = addenda.stream()
                    .collect(Collectors.groupingBy(a -> a.getContract().getId()));
            for (Contract contract : contracts) {
                result.put(contract.getId(), evaluate(contract, grouped.getOrDefault(contract.getId(), List.of())));
            }
        }
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public AddendumTermsPreview preview(Addendum draft) {
        requireReadAccess();
        if (draft.getContract() == null || draft.getEffectiveDate() == null) throw failure("termsInputRequired");
        UUID contractId = draft.getContract().getId();
        Contract contract = data.load(Contract.class).id(contractId).fetchPlan(FetchPlan.LOCAL)
                .optional().orElseThrow(() -> failure("termsUnavailable"));
        List<Addendum> history = loadAddenda(List.of(contractId), draft.getEffectiveDate());
        requireCompleteHistory(List.of(contractId), draft.getEffectiveDate(), history);
        List<Addendum> previous = history.stream().filter(a -> !a.getId().equals(draft.getId())).toList();
        if (previous.stream().anyMatch(a -> a.getEffectiveDate().equals(draft.getEffectiveDate()))) {
            throw failure("termsDuplicateDate");
        }
        ContractTerms before = evaluate(contract, previous);
        List<Addendum> withDraft = new ArrayList<>(previous);
        withDraft.add(draft);
        return new AddendumTermsPreview(before, evaluate(contract, withDraft));
    }

    List<Addendum> loadAddenda(Collection<UUID> ids, LocalDate date) {
        return data.load(Addendum.class)
                .query("select a from Addendum a where a.contract.id in :ids and a.effectiveDate <= :date order by a.effectiveDate")
                .parameter("ids", ids).parameter("date", date)
                .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL).add("contract", FetchPlan.LOCAL)).list();
    }

    /** Also used inside the save transaction, under system authentication, to enforce invariants. */
    ContractTerms evaluate(Contract contract, List<Addendum> addenda) {
        BigDecimal amount = contract.getTotalAmount();
        LocalDate end = contract.getEndDate();
        if (amount != null && amount.signum() < 0) throw failure("termsNegativeBase");
        Set<LocalDate> dates = new HashSet<>();
        for (Addendum a : addenda.stream().sorted(Comparator.comparing(Addendum::getEffectiveDate)).toList()) {
            if (!dates.add(a.getEffectiveDate())) throw failure("termsDuplicateDate");
            if (a.getAmountAdjustment() != null) {
                if (amount == null) throw failure("termsBaseRequired");
                BigDecimal adjustment = a.getAmountAdjustment();
                if (adjustment.stripTrailingZeros().scale() > 2 || adjustment.abs().compareTo(BigDecimal.TEN.pow(17)) >= 0) {
                    throw failure("termsAmountPrecision");
                }
                amount = amount.add(adjustment);
                if (amount.signum() < 0) throw addendumFailure("termsNegativeResult", a);
            }
            if (a.getNewEndDate() != null) {
                if (a.getNewEndDate().isBefore(contract.getStartDate())) throw addendumFailure("termsEndBeforeStart", a);
                end = a.getNewEndDate();
            }
        }
        return new ContractTerms(amount, end);
    }

    private void requireReadAccess() {
        if (!access.canRead(Contract.class, "totalAmount", "startDate", "endDate")
                || !access.canRead(Addendum.class, "contract", "effectiveDate", "amountAdjustment", "newEndDate", "number")) {
            throw failure("termsUnavailable");
        }
    }

    private void requireCompleteHistory(Collection<UUID> ids, LocalDate date, List<Addendum> visible) {
        Set<UUID> visibleIds = visible.stream().map(Addendum::getId).collect(Collectors.toSet());
        // Compare identity only under system authentication. Never expose hidden amounts or a partial total.
        Set<UUID> allIds = authenticator.withSystem(() -> loadAddenda(ids, date).stream()
                .map(Addendum::getId).collect(Collectors.toSet()));
        if (!visibleIds.equals(allIds)) throw failure("termsUnavailable");
    }

    private ContractTermsException addendumFailure(String key, Addendum addendum) {
        return new ContractTermsException(messages.formatMessage(ContractTermsService.class, key, addendum.getNumber()),
                addendum.getId());
    }

    ContractTermsException failure(String key, Object... args) {
        return new ContractTermsException(messages.formatMessage(ContractTermsService.class, key, args));
    }
}
