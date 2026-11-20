package ru.stnovator.finassist.service;

import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.SaveContext;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.Kind;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.test_support.RelatedFixtures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class ScheduleMutationGuardTest {
    @Autowired DataManager data;
    @Autowired SystemAuthenticator auth;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EffectiveScheduleService schedules;
    @Autowired ScheduleMutationGuard guard;
    RelatedFixtures fixture;
    final LocalDate date = LocalDate.of(2026, 6, 1);

    @BeforeEach void setup() { auth.begin("admin"); fixture = new RelatedFixtures(data, auth); }
    @AfterEach void cleanup() { try { fixture.close(); } finally { auth.end(); } }

    @Test void manualItemEditCannotCommitAcrossConfirmedSnapshot() throws Exception {
        var item = fixture.payment(date, "100");
        String before = fingerprint();
        assertBlocked(Set.of(fixture.contract.getId()), () -> {
            item.setAmount(new BigDecimal("101")); data.saveWithoutReload(item);
        }, () -> assertThat(fingerprint()).isEqualTo(before));
        assertThat(fingerprint()).isNotEqualTo(before);
    }

    @Test void correctionInsertInvalidatesPreview() throws Exception {
        var addendum = addendum();
        var correction = data.create(PaymentScheduleCorrection.class);
        correction.setProject(fixture.project); correction.setAddendum(addendum);
        String before = fingerprint();
        assertBlocked(Set.of(fixture.contract.getId()), () -> fixture.save(correction),
                () -> assertThat(fingerprint()).isEqualTo(before));
        assertThat(fingerprint()).isNotEqualTo(before);
    }

    @Test void correctionDeleteInvalidatesPreview() throws Exception {
        var correction = correction();
        String before = fingerprint();
        assertBlocked(Set.of(fixture.contract.getId()), () -> data.remove(correction),
                () -> assertThat(fingerprint()).isEqualTo(before));
        assertThat(fingerprint()).isNotEqualTo(before);
    }

    @Test void movingParentLocksOldAndNewContracts() throws Exception {
        try (var other = new RelatedFixtures(data, auth)) {
            // Test each contract independently: a union-only test can miss either lock.
            for (UUID locked : Set.of(fixture.contract.getId(), other.contract.getId())) {
                var project = data.load(Project.class).id(fixture.project.getId())
                        .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.BASE).add("contract", FetchPlan.LOCAL)).one();
                project.setContract(other.contract);
                var moving = project;
                assertBlocked(Set.of(locked), () -> data.saveWithoutReload(moving), () -> {});
                project = data.load(Project.class).id(fixture.project.getId())
                        .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.BASE).add("contract", FetchPlan.LOCAL)).one();
                project.setContract(fixture.contract); data.saveWithoutReload(project);
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"payment", "shipment", "paymentItem", "shipmentItem", "paymentCorrection", "shipmentCorrection", "paymentCorrectionItem", "shipmentCorrectionItem", "addendum", "project"})
    void directDeletionParticipates(String kind) throws Exception {
        Object target = target(kind);
        assertBlocked(Set.of(fixture.contract.getId()), () -> data.remove(target), () -> {});
    }

    @Test void hardDeletedCorrectionAlsoParticipates() throws Exception {
        var correction = correction();
        assertBlocked(Set.of(fixture.contract.getId()), () -> data.save(new SaveContext()
                .removing(correction).setHint(PersistenceHints.SOFT_DELETION, false)), () -> {});
    }

    @Test void termsValidationSeesFinalStateAcrossSeveralDataManagerCalls() {
        var contract = data.load(Contract.class).id(fixture.contract.getId()).one();
        contract.setTotalAmount(new BigDecimal("100")); data.saveWithoutReload(contract);
        var increase = data.create(Addendum.class); increase.setContract(contract);
        increase.setNumber("increase"); increase.setEffectiveDate(date);
        increase.setAmountAdjustment(new BigDecimal("20")); increase = fixture.save(increase);
        var decrease = data.create(Addendum.class); decrease.setContract(contract);
        decrease.setNumber("decrease"); decrease.setEffectiveDate(date.plusDays(1));
        decrease.setAmountAdjustment(new BigDecimal("-120")); decrease = fixture.save(decrease);
        var first = increase;
        var second = decrease;
        transaction().executeWithoutResult(status -> {
            data.remove(first);
            data.remove(second);
        });
        assertThat(data.load(Addendum.class).id(first.getId()).optional()).isEmpty();
        assertThat(data.load(Addendum.class).id(second.getId()).optional()).isEmpty();
    }

    @Test void lockRequiresExistingTransaction() {
        assertThatThrownBy(() -> guard.lockContracts(Set.of(fixture.contract.getId())))
                .isInstanceOf(IllegalStateException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"payment", "shipment", "paymentItem", "shipmentItem", "paymentCorrection", "shipmentCorrection", "paymentCorrectionItem", "shipmentCorrectionItem"})
    void movingChildLocksOldAndNewContracts(String kind) throws Exception {
        Object target = target(kind);
        String property = kind.endsWith("CorrectionItem") ? "correction" : kind.endsWith("Item") ? "schedule" : "project";
        Object original = EntityValues.getValue(target, property);
        try (var other = new RelatedFixtures(data, auth)) {
            Object destination = switch (kind) {
                case "paymentItem" -> other.payment;
                case "shipmentItem" -> other.shipment;
                case "paymentCorrectionItem", "shipmentCorrectionItem" -> {
                    var a = data.create(Addendum.class); a.setContract(other.contract);
                    a.setNumber("destination"); a.setEffectiveDate(date); a = other.save(a);
                    if (kind.equals("paymentCorrectionItem")) {
                        var c = data.create(PaymentScheduleCorrection.class); c.setProject(other.project); c.setAddendum(a);
                        yield other.save(c);
                    }
                    var c = data.create(ShipmentScheduleCorrection.class); c.setProject(other.project); c.setAddendum(a);
                    yield other.save(c);
                }
                default -> other.project;
            };
            if (kind.equals("payment")) data.remove(other.payment);
            if (kind.equals("shipment")) data.remove(other.shipment);
            try {
                for (UUID locked : Set.of(fixture.contract.getId(), other.contract.getId())) {
                    var moving = data.load(Id.of(target)).one();
                    EntityValues.setValue(moving, property, destination);
                    assertBlocked(Set.of(locked), () -> data.saveWithoutReload(moving), () -> {});
                    var restored = data.load(Id.of(target)).one();
                    EntityValues.setValue(restored, property, original); data.saveWithoutReload(restored);
                }
            } finally {
                var restored = data.load(Id.of(target)).one();
                EntityValues.setValue(restored, property, original); data.saveWithoutReload(restored);
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"paymentCorrection", "shipmentCorrection"})
    void movingCorrectionAddendumLocksBothContracts(String kind) throws Exception {
        Object target = target(kind);
        Object original = EntityValues.getValue(target, "addendum");
        try (var other = new RelatedFixtures(data, auth)) {
            var destination = data.create(Addendum.class); destination.setContract(other.contract);
            destination.setNumber("destination"); destination.setEffectiveDate(date); destination = other.save(destination);
            try {
                for (UUID locked : Set.of(fixture.contract.getId(), other.contract.getId())) {
                    var moving = data.load(Id.of(target)).one();
                    EntityValues.setValue(moving, "addendum", destination);
                    assertBlocked(Set.of(locked), () -> data.saveWithoutReload(moving), () -> {});
                    var restored = data.load(Id.of(target)).one();
                    EntityValues.setValue(restored, "addendum", original); data.saveWithoutReload(restored);
                }
            } finally {
                var restored = data.load(Id.of(target)).one();
                EntityValues.setValue(restored, "addendum", original); data.saveWithoutReload(restored);
            }
        }
    }

    Object target(String kind) {
        return switch (kind) {
            case "payment" -> fixture.payment;
            case "shipment" -> fixture.shipment;
            case "project" -> fixture.project;
            case "addendum" -> addendum();
            case "paymentItem" -> fixture.payment(date, "100");
            case "shipmentItem" -> fixture.shipment(date, "100");
            case "paymentCorrection" -> correction();
            case "shipmentCorrection" -> shipmentCorrection();
            case "paymentCorrectionItem" -> {
                var item = data.create(PaymentScheduleCorrectionItem.class); item.setCorrection(correction());
                item.setItemDate(date); item.setAmount(BigDecimal.TEN); yield fixture.save(item);
            }
            case "shipmentCorrectionItem" -> {
                var item = data.create(ShipmentScheduleCorrectionItem.class); item.setCorrection(shipmentCorrection());
                item.setItemDate(date); item.setAmount(BigDecimal.TEN); yield fixture.save(item);
            }
            default -> throw new IllegalArgumentException(kind);
        };
    }
    Addendum addendum() {
        var a = data.create(Addendum.class); a.setContract(fixture.contract);
        a.setNumber(UUID.randomUUID().toString()); a.setEffectiveDate(date); return fixture.save(a);
    }
    PaymentScheduleCorrection correction() {
        var c = data.create(PaymentScheduleCorrection.class); c.setProject(fixture.project);
        c.setAddendum(addendum()); return fixture.save(c);
    }
    ShipmentScheduleCorrection shipmentCorrection() {
        var c = data.create(ShipmentScheduleCorrection.class); c.setProject(fixture.project);
        c.setAddendum(addendum()); return fixture.save(c);
    }
    String fingerprint() { return schedules.load(fixture.project.getId(), Kind.PAYMENT, null).fingerprint(); }
    TransactionTemplate transaction() {
        var t = new TransactionTemplate(transactions);
        t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return t;
    }
    void assertBlocked(Set<UUID> contracts, Runnable mutation, Runnable whileLocked) throws Exception {
        var started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?>[] writer = new Future<?>[1];
            try {
                transaction().executeWithoutResult(status -> {
                    guard.lockContracts(contracts);
                    writer[0] = executor.submit(() -> auth.runWithUser("admin", () -> transaction().executeWithoutResult(s -> {
                        started.countDown(); mutation.run();
                    })));
                    try {
                        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                        assertThatThrownBy(() -> writer[0].get(500, TimeUnit.MILLISECONDS))
                                .as("manual mutation must not commit while confirmation holds the contract")
                                .isInstanceOf(TimeoutException.class);
                        whileLocked.run();
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                });
            } finally {
                if (writer[0] != null) writer[0].get(15, TimeUnit.SECONDS);
            }
        }
    }
}
