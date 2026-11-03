package ru.stnovator.finassist.service;

import io.jmix.core.DataManager;
import io.jmix.core.SaveContext;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.entity.Addendum;
import ru.stnovator.finassist.entity.Contract;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class ContractTermsServiceTest {
    @Autowired DataManager data;
    @Autowired SystemAuthenticator auth;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ContractTermsService service;
    final List<Contract> contracts = new ArrayList<>();
    Contract contract;
    final LocalDate jan = LocalDate.of(2026, 1, 10);

    @BeforeEach void setup() {
        auth.begin("admin");
        contract = createContract("1000000");
    }

    @AfterEach void cleanup() {
        try {
            for (Contract c : contracts) {
                data.load(Contract.class).id(c.getId()).optional().ifPresent(data::remove);
                var removed = data.load(Addendum.class)
                        .query("select a from Addendum a where a.contract.id = :id").parameter("id", c.getId())
                        .hint(PersistenceHints.SOFT_DELETION, false).list();
                SaveContext cleanup = new SaveContext().setHint(PersistenceHints.SOFT_DELETION, false);
                removed.forEach(cleanup::removing);
                data.load(Contract.class).id(c.getId()).hint(PersistenceHints.SOFT_DELETION, false)
                        .optional().ifPresent(cleanup::removing);
                data.save(cleanup);
            }
        } finally { auth.end(); }
    }

    @Test void accumulatesIndependentChangesAndExcludesFuture() {
        var first = save(jan, "200000", null);
        save(LocalDate.of(2026, 2, 1), null, LocalDate.of(2027, 3, 31));
        save(LocalDate.of(2026, 3, 15), "-50000", null);
        save(LocalDate.of(2026, 12, 1), "100000", null);
        assertAmount(jan.minusDays(1), "1000000");
        assertAmount(jan, "1200000");
        var terms = service.calculate(contract.getId(), LocalDate.of(2026, 11, 3));
        assertThat(terms.totalAmount()).isEqualByComparingTo("1150000");
        assertThat(terms.endDate()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(service.preview(first).after().totalAmount()).isEqualByComparingTo("1200000");
        assertThat(data.load(Contract.class).id(contract.getId()).one().getTotalAmount()).isEqualByComparingTo("1000000");
    }

    @Test void supportsZeroAndRejectsNegativeResult() {
        var a = save(jan, "-1000000", null);
        assertAmount(jan, "0");
        a.setAmountAdjustment(new BigDecimal("-1000000.01"));
        assertThatThrownBy(() -> data.save(a)).isInstanceOf(ContractTermsException.class);
        assertAmount(jan, "0");
    }

    @Test void preservesLegacyNullFieldsAndRequiresBaseForMoney() {
        contract.setTotalAmount(null); contract = data.save(contract);
        var legacy = save(jan, null, null);
        assertThat(legacy.getAmountAdjustment()).isNull();
        assertThat(legacy.getNewEndDate()).isNull();
        assertThat(service.calculate(contract.getId(), jan).totalAmount()).isNull();
        legacy.setNewEndDate(LocalDate.of(2027, 1, 1));
        var updated = data.save(legacy);
        updated.setAmountAdjustment(BigDecimal.ZERO);
        assertThatThrownBy(() -> data.save(updated)).isInstanceOf(ContractTermsException.class);
    }

    @Test void previewReplacesStoredVersionAndHonorsChangedDate() {
        var first = save(jan, "200000", null);
        var later = save(jan.plusMonths(1), "-50000", null);
        first.setAmountAdjustment(new BigDecimal("100000"));
        assertThat(service.preview(first).after().totalAmount()).isEqualByComparingTo("1100000");
        first.setEffectiveDate(jan.plusMonths(2));
        assertThat(service.preview(first).before().totalAmount()).isEqualByComparingTo("950000");
        assertThat(service.preview(first).after().totalAmount()).isEqualByComparingTo("1050000");
        assertThat(service.preview(later).after().totalAmount()).isEqualByComparingTo("1150000");
        assertAmount(jan, "1200000");
    }

    @Test void duplicateDatesRejectedButSelfEditAndOtherContractsAllowed() {
        var a = save(jan, "10", null);
        a.setAmountAdjustment(new BigDecimal("20")); data.save(a);
        assertThatThrownBy(() -> save(jan, "30", null)).isInstanceOf(ContractTermsException.class);
        Contract other = createContract("100");
        data.save(draft(other, jan, "1", null));
        var all = service.calculateAll(List.of(contract.getId(), other.getId()), jan);
        assertThat(all.get(other.getId()).totalAmount()).isEqualByComparingTo("101");
        assertThat(all.get(contract.getId()).totalAmount()).isEqualByComparingTo("1000020");
    }

    @Test void deletingOrEditingEarlierIncreaseCannotInvalidateLaterDecrease() {
        var first = save(jan, "200000", null);
        save(jan.plusMonths(1), "-1200000", null);
        assertThatThrownBy(() -> data.remove(first)).isInstanceOf(ContractTermsException.class);
        first.setAmountAdjustment(new BigDecimal("199999"));
        assertThatThrownBy(() -> data.save(first)).isInstanceOf(ContractTermsException.class);
        contract.setTotalAmount(new BigDecimal("999999"));
        assertThatThrownBy(() -> data.save(contract)).isInstanceOf(ContractTermsException.class);
        assertAmount(jan.plusMonths(1), "0");
    }

    @Test void deletionAndBackdatedInsertRecalculateWithoutStoredTotals() {
        var a = save(jan.plusMonths(1), "-100", null);
        save(jan, "10.01", null);
        assertAmount(jan.plusMonths(1), "999910.01");
        data.remove(a);
        assertAmount(jan.plusMonths(1), "1000010.01");
        save(jan.plusMonths(1), "-10.01", null);
        assertAmount(jan.plusMonths(1), "1000000");
    }

    @Test void rejectsInvalidTermAndMovingSavedAddendum() {
        assertThatThrownBy(() -> save(jan, null, LocalDate.of(2025, 12, 31)))
                .isInstanceOf(ContractTermsException.class);
        var a = save(jan, "1", null);
        a.setContract(createContract("200"));
        assertThatThrownBy(() -> data.save(a)).isInstanceOf(ContractTermsException.class);
    }

    @Test void parallelDecreasesCannotOverdrawContract() throws Exception {
        contract.setTotalAmount(new BigDecimal("100")); contract = data.save(contract);
        assertThat(concurrentSave(false)).isEqualTo(1);
        assertAmount(jan.plusDays(1), "40");
    }

    @Test void parallelDuplicateDatesCannotBothCommit() throws Exception {
        assertThat(concurrentSave(true)).isEqualTo(1);
        assertAmount(jan, "999940");
    }

    @Test void transactionCanRemoveWholeChainWithoutIntermediateNegativeState() {
        var increase = save(jan, "200000", null);
        var decrease = save(jan.plusDays(1), "-1200000", null);
        data.save(new SaveContext().removing(increase, decrease));
        assertAmount(jan.plusDays(1), "1000000");
    }

    @Test void excessivePrecisionIsRejectedBeforeDatabaseRounding() {
        assertThatThrownBy(() -> save(jan, "0.001", null)).isInstanceOf(ContractTermsException.class);
        assertAmount(jan, "1000000");
    }

    @Test void hiddenHistoryCannotProducePartialTotal() {
        var a = save(jan, "200000", null);
        a.setNumber("hidden"); data.save(a);
        var user = data.create(ru.stnovator.finassist.entity.User.class);
        user.setUsername("terms-restricted");
        ru.stnovator.finassist.test_support.DashboardFixtures.restricted(user, "terms-hidden-history");
        try {
            assertThatThrownBy(() -> service.calculate(contract.getId(), jan))
                    .isInstanceOf(ContractTermsException.class)
                    .hasMessageContaining("Недостаточно прав");
        } finally { auth.end(); auth.begin("admin"); }
    }

    @io.jmix.security.role.annotation.RowLevelRole(name = "Terms hidden history", code = "terms-hidden-history")
    public interface HiddenHistory {
        @io.jmix.security.role.annotation.JpqlRowLevelPolicy(entityClass = Addendum.class, where = "{E}.number <> 'hidden'")
        void addenda();
    }

    @Test void calculationUsesOneSnapshotDuringConcurrentBaseAndHistoryChange() {
        contract.setTotalAmount(new BigDecimal("100")); contract = data.save(contract);
        var once = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (once.getAndSet(false)) {
                var writer = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
                writer.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                writer.executeWithoutResult(status -> {
                    Contract updated = data.load(Contract.class).id(contract.getId()).one();
                    updated.setTotalAmount(new BigDecimal("200"));
                    data.save(new SaveContext().saving(updated, draft(updated, jan, "-100", null)));
                });
            }
            return invocation.callRealMethod();
        }).when(service).loadAddenda(org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.any());
        try {
            assertAmount(jan, "100"); // Both committed states are 100; a mixed state would incorrectly be 0.
        } finally { org.mockito.Mockito.reset(service); }
        assertAmount(jan, "100");
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test void validationDoesNotDiscloseNumberOfHiddenAddendum() {
        var hidden = save(jan, "-1000000", null);
        hidden.setNumber("hidden"); data.save(hidden);
        var user = data.create(ru.stnovator.finassist.entity.User.class); user.setUsername("terms-restricted");
        ru.stnovator.finassist.test_support.DashboardFixtures.restricted(user, "terms-hidden-history");
        try {
            contract.setTotalAmount(new BigDecimal("999999"));
            assertThatThrownBy(() -> data.save(contract)).isInstanceOf(ContractTermsException.class)
                    .hasMessage("Изменение нарушает условия договора. Недостаточно прав для просмотра подробностей.");
        } finally { auth.end(); auth.begin("admin"); }
    }

    @Test void baseEditAndDecreaseCannotCommitInvalidCombination() throws Exception {
        contract.setTotalAmount(new BigDecimal("100")); contract = data.save(contract);
        long successes = race(() -> {
            Contract c = data.load(Contract.class).id(contract.getId()).one();
            c.setTotalAmount(new BigDecimal("40")); data.save(c);
        }, () -> data.save(draft(contract, jan, "-60", null)));
        assertThat(successes).isEqualTo(1);
        assertAmount(jan, "40");
    }

    @Test void deletingIncreaseAndAddingDecreaseCannotCommitInvalidCombination() throws Exception {
        contract.setTotalAmount(new BigDecimal("100")); contract = data.save(contract);
        var increase = save(jan, "100", null);
        long successes = race(() -> data.remove(increase),
                () -> data.save(draft(contract, jan.plusDays(1), "-150", null)));
        assertThat(successes).isEqualTo(1);
        assertThat(service.calculate(contract.getId(), jan.plusDays(1)).totalAmount())
                .isBetween(BigDecimal.ZERO, new BigDecimal("100"));
    }

    private long race(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Runnable operation : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return auth.withUser("admin", () -> {
                        try { operation.run(); return true; }
                        catch (ContractTermsException e) { return false; }
                    });
                }));
            }
            start.countDown();
            long successes = 0;
            for (var f : futures) if (f.get(30, TimeUnit.SECONDS)) successes++;
            return successes;
        } finally { pool.shutdownNow(); }
    }

    private long concurrentSave(boolean sameDate) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                LocalDate date = sameDate ? jan : jan.plusDays(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return auth.withUser("admin", () -> {
                        try { data.save(draft(contract, date, "-60", null)); return true; }
                        catch (ContractTermsException e) { return false; }
                    });
                }));
            }
            start.countDown();
            long successes = 0;
            for (var f : futures) if (f.get(30, TimeUnit.SECONDS)) successes++;
            return successes;
        } finally { pool.shutdownNow(); }
    }

    private Contract createContract(String amount) {
        var c = data.create(Contract.class);
        c.setInternalID("terms-" + UUID.randomUUID());
        c.setStartDate(LocalDate.of(2026, 1, 1)); c.setEndDate(LocalDate.of(2026, 12, 31));
        c.setTotalAmount(new BigDecimal(amount));
        c = data.save(c); contracts.add(c); return c;
    }

    private Addendum save(LocalDate date, String amount, LocalDate end) {
        return data.save(draft(contract, date, amount, end));
    }

    private Addendum draft(Contract c, LocalDate date, String amount, LocalDate end) {
        var a = data.create(Addendum.class); a.setContract(c); a.setNumber(UUID.randomUUID().toString());
        a.setEffectiveDate(date); a.setAmountAdjustment(amount == null ? null : new BigDecimal(amount));
        a.setNewEndDate(end); return a;
    }

    private void assertAmount(LocalDate date, String amount) {
        assertThat(service.calculate(contract.getId(), date).totalAmount()).isEqualByComparingTo(amount);
    }
}
