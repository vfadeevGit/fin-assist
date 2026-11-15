package ru.stnovator.finassist.ai.schedule;

import io.jmix.aitoolsflowuidata.entity.AiConversationEntity;
import io.jmix.core.*;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.event.EntityChangedEvent;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.model.EntityAttributePolicyAction;
import io.jmix.security.role.annotation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.service.ScheduleMutationGuard;
import ru.stnovator.finassist.test_support.DashboardFixtures;
import ru.stnovator.finassist.test_support.RelatedFixtures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

@SpringBootTest
@Import(ScheduleImportCommitServiceTest.FailureConfiguration.class)
class ScheduleImportCommitServiceTest {
    @Autowired ScheduleImportCommitService commits;
    @Autowired ScheduleImportWorkflow workflow;
    @Autowired ScheduleImportStore store;
    @Autowired DataManager data;
    @Autowired Metadata metadata;
    @Autowired UnconstrainedDataManager internal;
    @Autowired SystemAuthenticator auth;
    @Autowired ScheduleMutationGuard guard;
    @Autowired PlatformTransactionManager transactions;
    @Autowired LateFailure failure;
    RelatedFixtures fixture;
    UUID conversation;
    final String owner = "commit-" + UUID.randomUUID();
    final LocalDate date = LocalDate.of(2026, 6, 1);

    @BeforeEach void setup() {
        auth.begin("admin"); fixture = new RelatedFixtures(data, auth);
        var c = internal.create(AiConversationEntity.class); c.setUsername(owner); c.setTitle("commit");
        conversation = internal.save(c).getId(); login();
    }
    void login() { login(owner, "ROLE_finance-manager-role"); }
    void login(String username, String... roles) {
        var user = internal.create(User.class); user.setUsername(username); DashboardFixtures.authenticate(user, roles);
    }
    @AfterEach void cleanup() {
        failure.project = null;
        auth.runWithUser("admin", () -> {
            internal.load(AiScheduleImport.class).all().list().stream().filter(e -> owner.equals(e.getOwner())).forEach(internal::remove);
            internal.load(AiConversationEntity.class).id(conversation).optional().ifPresent(internal::remove);
            // Imports create records outside RelatedFixtures; remove every such record, including soft-deleted old rows.
            for (String entity : List.of("PaymentScheduleCorrectionItem", "ShipmentScheduleCorrectionItem", "PaymentScheduleCorrection", "ShipmentScheduleCorrection", "PaymentScheduleItem", "ShipmentScheduleItem", "PaymentSchedule", "ShipmentSchedule", "Addendum")) {
                String path = entity.endsWith("CorrectionItem") ? "correction.project.contract" : entity.endsWith("Item") ? "schedule.project.contract" : entity.equals("Addendum") ? "contract" : "project.contract";
                internal.load(metadata.getClass(entity).getJavaClass()).query("select e from " + entity + " e where e." + path + ".id = :contract")
                        .parameter("contract", fixture.contract.getId()).hint(PersistenceHints.SOFT_DELETION, false).list()
                        .forEach(e -> internal.save(new SaveContext().removing(e).setHint(PersistenceHints.SOFT_DELETION, false)));
            }
        });
        try { fixture.close(); } finally { auth.end(); }
    }
    ScheduleImportPreview preview(Kind kind, Mode mode) {
        var draft = workflow.start(conversation, kind);
        draft = workflow.setRows(draft.operationId(), draft.revision(), List.of(
                new InputRow(date.toString(), "120", "1"), new InputRow(date.plusDays(1).toString(), "80", "2")), null);
        workflow.selectContract(draft.operationId(), draft.revision(), fixture.contract.getId());
        draft = store.load(draft.operationId());
        return workflow.preview(draft.operationId(), draft.revision(), mode, "confirmed", date.plusYears(1));
    }
    ScheduleImportCommitService.CommitResult confirm(ScheduleImportPreview p) { return commits.confirm(p.operationId(), p.revision()); }
    String prefix(Kind kind) { return kind == Kind.PAYMENT ? "Payment" : "Shipment"; }
    List<?> items(Kind kind, UUID header, boolean correction) {
        String entity = prefix(kind) + "Schedule" + (correction ? "CorrectionItem" : "Item");
        return internal.load(metadata.getClass(entity).getJavaClass()).query("select e from " + entity + " e where e." + (correction ? "correction" : "schedule") + ".id = :id order by e.itemDate")
                .parameter("id", header).list();
    }
    void assertRows(List<?> rows) {
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(i -> EntityValues.<LocalDate>getValue(i, "itemDate")).toList()).containsExactly(date, date.plusDays(1));
        assertThat(rows.stream().map(i -> EntityValues.<BigDecimal>getValue(i, "amount")).toList()).containsExactly(new BigDecimal("120.00"), new BigDecimal("80.00"));
    }
    void assertPending(ScheduleImportPreview p) {
        var draft = store.load(p.operationId());
        assertThat(draft.status()).isEqualTo(ScheduleImportStore.Status.PREVIEW_READY);
        assertThat(draft.revision()).isEqualTo(p.revision()); assertThat(draft.resultId()).isNull();
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void createAndFillBothKinds(Kind kind) {
        Object original = kind == Kind.PAYMENT ? fixture.payment : fixture.shipment;
        var p = preview(kind, Mode.FILL); var result = confirm(p);
        assertThat(result.entityName()).isEqualTo(prefix(kind) + "Schedule");
        assertThat(result.entityId()).isEqualTo(EntityValues.getId(original)); assertRows(items(kind, result.entityId(), false));
        internal.save(new SaveContext().removing(internal.load(Id.of(original)).one()).setHint(PersistenceHints.SOFT_DELETION, false));
        var created = confirm(preview(kind, Mode.CREATE));
        assertThat(created.entityId()).isNotEqualTo(result.entityId()); assertRows(items(kind, created.entityId(), false));
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void replaceRetainsHeaderAndRemovesOldItems(Kind kind) {
        Object old = kind == Kind.PAYMENT ? fixture.payment(date, "10") : fixture.shipment(date, "10");
        Object header = kind == Kind.PAYMENT ? fixture.payment : fixture.shipment;
        var p = preview(kind, Mode.REPLACE); var result = confirm(p);
        assertThat(result.entityId()).isEqualTo(EntityValues.getId(header));
        assertThat(data.load(Id.of(old)).optional()).isEmpty(); assertRows(items(kind, result.entityId(), false));
        assertThat(EntityValues.<String>getValue(internal.load(Id.of(header)).one(), "createdBy")).isEqualTo("admin");
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void addendumStoresFullSnapshot(Kind kind) {
        if (kind == Kind.PAYMENT) fixture.payment(date, "100"); else fixture.shipment(date, "100");
        var before = data.load(Contract.class).id(fixture.contract.getId()).one();
        var result = confirm(preview(kind, Mode.ADDENDUM));
        assertThat(result.entityName()).isEqualTo("Addendum");
        var addendum = data.load(Addendum.class).id(result.entityId()).one();
        assertThat(addendum.getAmountAdjustment()).isNull(); assertThat(addendum.getNewEndDate()).isNull();
        assertThat(addendum.getCreatedBy()).isEqualTo(owner);
        String entity = prefix(kind) + "ScheduleCorrection";
        var corrections = internal.load(metadata.getClass(entity).getJavaClass()).query("select e from " + entity + " e where e.addendum.id = :id").parameter("id", result.entityId()).list();
        assertThat(corrections).hasSize(1); assertRows(items(kind, (UUID) EntityValues.getId(corrections.getFirst()), true));
        String opposite = prefix(kind == Kind.PAYMENT ? Kind.SHIPMENT : Kind.PAYMENT) + "ScheduleCorrection";
        assertThat(internal.load(metadata.getClass(opposite).getJavaClass()).query("select e from " + opposite + " e where e.addendum.id = :id").parameter("id", result.entityId()).list()).isEmpty();
        var after = data.load(Contract.class).id(fixture.contract.getId()).one();
        assertThat(after.getTotalAmount()).isEqualTo(before.getTotalAmount()); assertThat(after.getEndDate()).isEqualTo(before.getEndDate());
        assertThat(items(kind, kind == Kind.PAYMENT ? fixture.payment.getId() : fixture.shipment.getId(), false)).hasSize(1);
    }
    @ParameterizedTest @EnumSource(value=Mode.class, names={"REPLACE", "ADDENDUM"})
    void failureOnLastItemRollsBackEverything(Mode mode) {
        var old = fixture.payment(date, "100"); var p = preview(Kind.PAYMENT, mode); var before = counts();
        failure.project = fixture.project.getId(); failure.lastDate = date.plusDays(1);
        assertThatThrownBy(() -> confirm(p)).hasStackTraceContaining("intentional late item failure");
        failure.project = null;
        assertThat(counts()).isEqualTo(before); assertPending(p);
        assertThat(data.load(PaymentScheduleItem.class).id(old.getId()).one().getAmount()).isEqualByComparingTo("100");
        assertThat(items(Kind.PAYMENT, fixture.payment.getId(), false)).hasSize(1);
    }
    @Test void receiptRollsBackWithOuterTransactionFailure() {
        fixture.payment(date, "100");
        var p = preview(Kind.PAYMENT, Mode.ADDENDUM); var before = counts();
        assertThatThrownBy(() -> transaction().executeWithoutResult(s -> {
            confirm(p); assertThat(store.load(p.operationId()).status()).isEqualTo(ScheduleImportStore.Status.SAVED);
            throw new IllegalStateException("late outer failure");
        })).hasMessage("late outer failure");
        assertThat(counts()).isEqualTo(before); assertPending(p);
    }
    @Test void retryAfterLostResponseReturnsSameId() {
        fixture.payment(date, "100");
        var p = preview(Kind.PAYMENT, Mode.ADDENDUM); var first = confirm(p); var before = counts();
        var retry = confirm(p);
        assertThat(first.alreadySaved()).isFalse(); assertThat(retry.alreadySaved()).isTrue();
        assertThat(retry.entityId()).isEqualTo(first.entityId()); assertThat(retry.entityName()).isEqualTo("Addendum");
        assertThat(counts()).isEqualTo(before);
        assertThatThrownBy(() -> commits.confirm(p.operationId(), p.revision() + 1)).isInstanceOf(ConcurrentModificationException.class);
        login(owner, "ROLE_finance-manager-role", "ROW_LEVEL_ROLE_commit-hide-result");
        assertThatThrownBy(() -> confirm(p)).isInstanceOf(RuntimeException.class);
        login("intruder", "ROLE_system-full-access");
        assertThatThrownBy(() -> confirm(p)).isInstanceOf(SecurityException.class);
    }
    @Test void staleRevisionOrHistoryRequiresNewPreview() {
        var item = fixture.payment(date, "100"); var p = preview(Kind.PAYMENT, Mode.REPLACE);
        assertThatThrownBy(() -> commits.confirm(p.operationId(), p.revision() - 1)).isInstanceOf(ConcurrentModificationException.class);
        item.setAmount(new BigDecimal("101")); data.saveWithoutReload(item);
        assertThatThrownBy(() -> confirm(p)).isInstanceOf(ConcurrentModificationException.class); assertPending(p);
        assertThat(items(Kind.PAYMENT, fixture.payment.getId(), false)).hasSize(1);
    }
    @Test void deniedAttributeOrDeletePermissionPreventsWholeSave() {
        var old = fixture.payment(date, "100"); var p = preview(Kind.PAYMENT, Mode.REPLACE); var before = counts();
        for (String role : List.of("commit-no-delete", "commit-readonly-amount")) {
            login(owner, "ROLE_" + role);
            assertThatThrownBy(() -> confirm(p)).isInstanceOf(io.jmix.core.security.AccessDeniedException.class);
            login(); assertPending(p); assertThat(counts()).isEqualTo(before);
            assertThat(data.load(PaymentScheduleItem.class).id(old.getId()).one().getAmount()).isEqualByComparingTo("100");
        }
    }
    @Test void actualSaveUsesCompleteGraphForRowPolicies() {
        fixture.payment(date, "100");
        var p = preview(Kind.PAYMENT, Mode.ADDENDUM);
        login(owner, "ROLE_finance-manager-role", "ROW_LEVEL_ROLE_commit-complete-addendum");
        assertThat(confirm(p).entityName()).isEqualTo("Addendum");
    }
    @ParameterizedTest @EnumSource(value=Mode.class, names={"CREATE", "FILL", "REPLACE"})
    void actualBaseSaveUsesCompleteProposedAndOldGraphs(Mode mode) {
        if (mode == Mode.CREATE) internal.save(new SaveContext().removing(fixture.payment).setHint(PersistenceHints.SOFT_DELETION, false));
        if (mode == Mode.REPLACE) fixture.payment(date, "100");
        var p = preview(Kind.PAYMENT, mode);
        login(owner, "ROLE_finance-manager-role", "ROW_LEVEL_ROLE_commit-complete-base");
        var result = confirm(p); assertRows(items(Kind.PAYMENT, result.entityId(), false));
    }
    @Test void revisedDraftCannotBeConfirmedWithAnEarlierPreview() {
        var first = preview(Kind.PAYMENT, Mode.FILL);
        var changed = workflow.setRows(first.operationId(), first.revision(), List.of(new InputRow(date.toString(), "333", "new")), null);
        var current = workflow.preview(changed.operationId(), changed.revision(), Mode.FILL, null, null);
        assertThatThrownBy(() -> confirm(first)).isInstanceOf(ConcurrentModificationException.class);
        assertPending(current); assertThat(items(Kind.PAYMENT, fixture.payment.getId(), false)).isEmpty();
        assertThat(confirm(current).alreadySaved()).isFalse();
        assertThat(data.load(PaymentScheduleItem.class).query("select e from PaymentScheduleItem e where e.schedule.id = :id")
                .parameter("id", fixture.payment.getId()).one().getAmount()).isEqualByComparingTo("333");
    }
    @Test void missingOperationAndForeignOwnerCannotConfirm() {
        var p = preview(Kind.PAYMENT, Mode.FILL);
        assertThatThrownBy(() -> commits.confirm(UUID.randomUUID(), p.revision())).isInstanceOf(SecurityException.class);
        login("intruder", "ROLE_system-full-access");
        assertThatThrownBy(() -> confirm(p)).isInstanceOf(SecurityException.class);
        login(); assertPending(p); assertThat(items(Kind.PAYMENT, fixture.payment.getId(), false)).isEmpty();
    }
    @Test void deletedCorrectionRequiresNewPreview() {
        fixture.payment(date, "100");
        var a = data.create(Addendum.class); a.setContract(fixture.contract); a.setNumber("earlier"); a.setEffectiveDate(date); a = fixture.save(a);
        var c = data.create(PaymentScheduleCorrection.class); c.setProject(fixture.project); c.setAddendum(a); c = fixture.save(c);
        var p = preview(Kind.PAYMENT, Mode.ADDENDUM);
        data.remove(c);
        assertThatThrownBy(() -> confirm(p)).isInstanceOf(ConcurrentModificationException.class); assertPending(p);
    }
    @Test void concurrentRetriesCreateOneAddendumAndReceipt() throws Exception {
        fixture.payment(date, "100"); var p = preview(Kind.PAYMENT, Mode.ADDENDUM);
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<ScheduleImportCommitService.CommitResult> call = () -> {
                login(); ready.countDown();
                try {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return confirm(p);
                } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
            };
            var first = executor.submit(call); var second = executor.submit(call);
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); } finally { start.countDown(); }
            var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(results).extracting(ScheduleImportCommitService.CommitResult::entityId).containsOnly(results.getFirst().entityId());
            assertThat(results).extracting(ScheduleImportCommitService.CommitResult::alreadySaved).containsExactlyInAnyOrder(false, true);
            assertThat(data.load(Addendum.class).query("select e from Addendum e where e.contract.id = :id")
                    .parameter("id", fixture.contract.getId()).list()).hasSize(1);
            assertThat(store.load(p.operationId()).resultId()).isEqualTo(results.getFirst().entityId());
        }
    }
    @Test void concurrentCorrectionCommittedBeforeLockInvalidatesConfirmation() {
        fixture.payment(date, "100"); var p = preview(Kind.PAYMENT, Mode.REPLACE);
        var started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?>[] worker = new Future<?>[1];
            transaction().executeWithoutResult(status -> {
                guard.lockContracts(Set.of(fixture.contract.getId()));
                worker[0] = executor.submit(() -> {
                    login(); started.countDown();
                    try { return confirm(p); } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
                });
                try {
                    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> worker[0].get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
                var a = data.create(Addendum.class); a.setContract(fixture.contract); a.setNumber("concurrent"); a.setEffectiveDate(date); a = fixture.save(a);
                var c = data.create(PaymentScheduleCorrection.class); c.setProject(fixture.project); c.setAddendum(a); fixture.save(c);
            });
            assertThatThrownBy(() -> worker[0].get(15, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
        assertPending(p); assertThat(items(Kind.PAYMENT, fixture.payment.getId(), false)).hasSize(1);
    }
    List<Long> counts() {
        return java.util.stream.Stream.of("PaymentSchedule", "ShipmentSchedule", "PaymentScheduleItem", "ShipmentScheduleItem", "Addendum", "PaymentScheduleCorrection", "ShipmentScheduleCorrection", "PaymentScheduleCorrectionItem", "ShipmentScheduleCorrectionItem")
                .map(e -> internal.loadValue("select count(e) from " + e + " e", Long.class).one()).toList();
    }
    TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactions); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); return tx;
    }
    @TestConfiguration static class FailureConfiguration { @Bean LateFailure lateFailure(UnconstrainedDataManager data) { return new LateFailure(data); } }
    static class LateFailure {
        final UnconstrainedDataManager data; UUID project; LocalDate lastDate;
        LateFailure(UnconstrainedDataManager data) { this.data = data; }
        @EventListener public void changed(EntityChangedEvent<?> event) {
            if (project == null || event.getType() != EntityChangedEvent.Type.CREATED) return;
            Class<?> type = event.getEntityId().getEntityClass();
            if (type != PaymentScheduleItem.class && type != PaymentScheduleCorrectionItem.class) return;
            Object item = data.load(event.getEntityId()).one();
            String path = type == PaymentScheduleItem.class ? "schedule.project.id" : "correction.project.id";
            if (project.equals(EntityValues.getValueEx(item, path)) && lastDate.equals(EntityValues.getValue(item, "itemDate")))
                throw new IllegalStateException("intentional late item failure");
        }
    }
    @RowLevelRole(name="Commit complete addendum", code="commit-complete-addendum")
    public interface CompleteAddendum {
        @PredicateRowLevelPolicy(entityClass=Addendum.class, actions=io.jmix.security.model.RowLevelPolicyAction.CREATE)
        default io.jmix.security.model.RowLevelBiPredicate<Addendum, org.springframework.context.ApplicationContext> complete() {
            return (a, ctx) -> a.getPaymentScheduleCorrections() != null && a.getPaymentScheduleCorrections().size() == 1
                    && a.getShipmentScheduleCorrections() != null && a.getShipmentScheduleCorrections().isEmpty()
                    && a.getPaymentScheduleCorrections().getFirst().getAddendum() == a
                    && a.getPaymentScheduleCorrections().getFirst().getItems() != null
                    && a.getPaymentScheduleCorrections().getFirst().getItems().size() == 2
                    && a.getPaymentScheduleCorrections().getFirst().getItems().stream().allMatch(i -> i.getCorrection() == a.getPaymentScheduleCorrections().getFirst());
        }
    }
    @RowLevelRole(name="Commit complete base", code="commit-complete-base")
    public interface CompleteBase {
        @PredicateRowLevelPolicy(entityClass=PaymentSchedule.class, actions={io.jmix.security.model.RowLevelPolicyAction.CREATE, io.jmix.security.model.RowLevelPolicyAction.UPDATE})
        default io.jmix.security.model.RowLevelBiPredicate<PaymentSchedule, org.springframework.context.ApplicationContext> header() {
            // DataManager checks a managed merge: linked instances can differ while persistent identities agree.
            return (h, ctx) -> h.getItems() != null && h.getItems().size() == 2
                    && h.getProject().getPaymentSchedule().getId().equals(h.getId())
                    && h.getItems().stream().allMatch(i -> i.getSchedule().getId().equals(h.getId()))
                    && h.getItems().stream().map(PaymentScheduleItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(new BigDecimal("200")) == 0;
        }
        @PredicateRowLevelPolicy(entityClass=PaymentScheduleItem.class, actions=io.jmix.security.model.RowLevelPolicyAction.DELETE)
        default io.jmix.security.model.RowLevelBiPredicate<PaymentScheduleItem, org.springframework.context.ApplicationContext> oldItem() {
            return (i, ctx) -> i.getAmount().compareTo(new BigDecimal("100")) == 0
                    && i.getSchedule().getItems() != null && i.getSchedule().getItems().size() == 1
                    && i.getSchedule().getItems().getFirst().getId().equals(i.getId());
        }
    }
    @RowLevelRole(name="Commit hidden result", code="commit-hide-result")
    public interface HiddenResult { @JpqlRowLevelPolicy(entityClass=Addendum.class, where="1 = 0") void hidden(); }
    @ResourceRole(name="Commit no delete", code="commit-no-delete")
    public interface NoDelete {
        @EntityPolicy(entityName="*", actions={EntityPolicyAction.READ, EntityPolicyAction.CREATE, EntityPolicyAction.UPDATE})
        @EntityAttributePolicy(entityName="*", attributes="*", action=EntityAttributePolicyAction.MODIFY) void all();
    }
    @ResourceRole(name="Commit readonly amount", code="commit-readonly-amount")
    public interface ReadonlyAmount {
        @EntityPolicy(entityName="*", actions=EntityPolicyAction.ALL)
        @EntityAttributePolicy(entityName="*", attributes="*", action=EntityAttributePolicyAction.VIEW)
        @EntityAttributePolicy(entityClass=PaymentSchedule.class, attributes="*", action=EntityAttributePolicyAction.MODIFY)
        @EntityAttributePolicy(entityClass=PaymentScheduleItem.class, attributes={"schedule", "itemDate"}, action=EntityAttributePolicyAction.MODIFY)
        void all();
    }
}
