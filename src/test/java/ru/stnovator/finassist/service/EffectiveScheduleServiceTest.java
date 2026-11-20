package ru.stnovator.finassist.service;

import io.jmix.core.DataManager;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.AccessDeniedException;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.Kind;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.test_support.DashboardFixtures;
import ru.stnovator.finassist.test_support.RelatedFixtures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class EffectiveScheduleServiceTest {
    @Autowired DataManager data;
    @Autowired SystemAuthenticator auth;
    @Autowired EffectiveScheduleService service;
    RelatedFixtures fixtures;
    final LocalDate rowDate = LocalDate.of(2026, 6, 1);

    @BeforeEach void setup() {
        auth.begin("admin");
        fixtures = new RelatedFixtures(data, auth);
    }

    @AfterEach void cleanup() {
        try { fixtures.close(); } finally { auth.end(); }
    }

    @Test void selectsLatestNonemptyPerKind() {
        fixtures.payment(rowDate, "100");
        fixtures.shipment(rowDate, "50");
        var payment = addendum(2);
        correction(fixtures, Kind.PAYMENT, payment, "120");
        var shipment = addendum(3);
        correction(fixtures, Kind.SHIPMENT, shipment, "200");

        var all = service.loadAll(Set.of(fixtures.project.getId()), null);
        var payments = all.get(new EffectiveScheduleService.Key(fixtures.project.getId(), Kind.PAYMENT));
        var shipments = all.get(new EffectiveScheduleService.Key(fixtures.project.getId(), Kind.SHIPMENT));
        assertThat(all).hasSize(2);
        assertThat(payments.sourceAddendumId()).isEqualTo(payment.getId());
        assertThat(payments.baseScheduleId()).isEqualTo(fixtures.payment.getId());
        assertAmount(payments, "120");
        assertThat(shipments.sourceAddendumId()).isEqualTo(shipment.getId());
        assertAmount(shipments, "200");
    }

    @Test void futureAndEmptyHistoryHasDifferentSelectionAndGuardSemantics() {
        fixtures.payment(rowDate, "100");
        var first = addendum(2);
        correction(fixtures, Kind.PAYMENT, first, "120");
        correction(fixtures, Kind.PAYMENT, addendum(3), null);
        var future = addendum(12);
        correction(fixtures, Kind.PAYMENT, future, "150");
        correction(fixtures, Kind.PAYMENT, addendum(11), null);

        var bounded = service.load(fixtures.project.getId(), Kind.PAYMENT, rowDate);
        assertAmount(bounded, "120");
        assertThat(bounded.sourceAddendumId()).isEqualTo(first.getId());
        assertThat(bounded.hasCorrections()).isTrue();
        assertThat(bounded.latestCorrectionDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        var unbounded = service.load(fixtures.project.getId(), Kind.PAYMENT, null);
        assertAmount(unbounded, "150");
        assertThat(unbounded.sourceAddendumId()).isEqualTo(future.getId());
        assertThat(unbounded.latestCorrectionDate()).isEqualTo(future.getEffectiveDate());
    }

    @Test void emptyCorrectionStillGuardsBaseAndLatestDate() {
        fixtures.payment(rowDate, "100");
        correction(fixtures, Kind.PAYMENT, addendum(12), null);
        var snapshot = service.load(fixtures.project.getId(), Kind.PAYMENT, null);
        assertAmount(snapshot, "100");
        assertThat(snapshot.sourceAddendumId()).isNull();
        assertThat(snapshot.hasCorrections()).isTrue();
        assertThat(snapshot.latestCorrectionDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        var earlier = service.load(fixtures.project.getId(), Kind.PAYMENT, rowDate);
        assertThat(earlier.hasCorrections()).isFalse();
        assertThat(earlier.latestCorrectionDate()).isNull();
    }

    @Test void doesNotMixProjects() {
        fixtures.payment(rowDate, "100");
        try (var other = new RelatedFixtures(data, auth)) {
            other.project.setContract(fixtures.contract);
            other.project = data.save(other.project);
            other.payment(rowDate, "900");
            var addendum = data.create(Addendum.class);
            addendum.setContract(fixtures.contract);
            addendum.setNumber("other-project");
            addendum.setEffectiveDate(rowDate);
            correction(other, Kind.PAYMENT, other.save(addendum), "999");
            var all = service.loadAll(Set.of(fixtures.project.getId(), other.project.getId()), null);
            assertThat(all).hasSize(4);
            assertAmount(all.get(new EffectiveScheduleService.Key(fixtures.project.getId(), Kind.PAYMENT)), "100");
            assertAmount(all.get(new EffectiveScheduleService.Key(other.project.getId(), Kind.PAYMENT)), "999");
        }
    }

    @Test void inclusiveCutoff() {
        fixtures.payment(rowDate, "100");
        var addendum = addendum(6);
        correction(fixtures, Kind.PAYMENT, addendum, "120");
        assertAmount(service.load(fixtures.project.getId(), Kind.PAYMENT, rowDate.minusDays(1)), "100");
        assertAmount(service.load(fixtures.project.getId(), Kind.PAYMENT, rowDate), "120");
    }

    @ParameterizedTest
    @ValueSource(strings = {"related-item-jpql", "related-item-predicate", "comparison-hidden-item",
            "related-header-predicate", "terms-hidden-history", "effective-hidden-empty-correction"})
    void hiddenRowsMakeHistoryUnavailable(String role) {
        fixtures.shipment(rowDate, "999");
        var addendum = addendum(2);
        addendum.setNumber("hidden");
        addendum = data.save(addendum);
        correction(fixtures, Kind.SHIPMENT, addendum, "999");
        correction(fixtures, Kind.SHIPMENT, addendum(3), null);
        var user = data.create(User.class);
        user.setUsername("effective-restricted");
        DashboardFixtures.restricted(user, role);
        assertThatThrownBy(() -> service.load(fixtures.project.getId(), Kind.SHIPMENT, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageNotContaining(addendum.getId().toString());
    }

    @Test void missingProjectIsAccessFailureRatherThanEmptySchedule() {
        assertThatThrownBy(() -> service.load(UUID.randomUUID(), Kind.PAYMENT, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void unreadableAttributesAreAnAccessFailure() {
        fixtures.shipment(rowDate, "100");
        var user = data.create(User.class);
        user.setUsername("effective-attribute-restricted");
        DashboardFixtures.authenticate(user, "ROLE_related-payment-reader");
        assertThatThrownBy(() -> service.load(fixtures.project.getId(), Kind.SHIPMENT, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void fingerprintDetectsEditsOfUnselectedRowsAndEmptyHistory() {
        var baseItem = fixtures.payment(rowDate, "100");
        var older = addendum(2);
        var oldCorrection = correction(fixtures, Kind.PAYMENT, older, "110");
        correction(fixtures, Kind.PAYMENT, addendum(3), "120");
        var original = loadPayment();
        assertThat(loadPayment().fingerprint()).isEqualTo(original.fingerprint());
        baseItem.setAmount(new BigDecimal("101"));
        data.saveWithoutReload(baseItem);
        var afterBaseEdit = loadPayment();
        assertThat(afterBaseEdit.fingerprint()).isNotEqualTo(original.fingerprint());
        assertAmount(afterBaseEdit, "120");
        var oldItem = data.load(PaymentScheduleCorrectionItem.class)
                .query("select i from PaymentScheduleCorrectionItem i where i.correction.id = :id")
                .parameter("id", EntityValues.getId(oldCorrection)).one();
        oldItem.setItemDate(rowDate.plusDays(1));
        data.saveWithoutReload(oldItem);
        var afterOldItemEdit = loadPayment();
        assertThat(afterOldItemEdit.fingerprint()).isNotEqualTo(afterBaseEdit.fingerprint());
        older.setEffectiveDate(LocalDate.of(2026, 1, 1));
        data.saveWithoutReload(older);
        var afterHeaderEdit = loadPayment();
        assertThat(afterHeaderEdit.fingerprint()).isNotEqualTo(afterOldItemEdit.fingerprint());
        var empty = correction(fixtures, Kind.PAYMENT, addendum(4), null);
        var afterEmpty = loadPayment();
        assertThat(afterEmpty.fingerprint()).isNotEqualTo(afterHeaderEdit.fingerprint());
        assertAmount(afterEmpty, "120");
        data.remove(empty);
        assertThat(loadPayment().fingerprint()).isNotEqualTo(afterEmpty.fingerprint());
    }

    private EffectiveScheduleService.Snapshot loadPayment() {
        return service.load(fixtures.project.getId(), Kind.PAYMENT, null);
    }

    private void assertAmount(EffectiveScheduleService.Snapshot snapshot, String amount) {
        assertThat(snapshot.rows()).singleElement().satisfies(row -> {
            assertThat(row.date()).isEqualTo(rowDate);
            assertThat(row.amount()).isEqualByComparingTo(amount);
        });
    }

    private Addendum addendum(int month) {
        var addendum = data.create(Addendum.class);
        addendum.setContract(fixtures.contract);
        addendum.setNumber("effective-" + month);
        addendum.setEffectiveDate(LocalDate.of(2026, month, 1));
        return fixtures.save(addendum);
    }

    private Object correction(RelatedFixtures fixture, Kind kind, Addendum addendum, String amount) {
        if (kind == Kind.PAYMENT) {
            var correction = data.create(PaymentScheduleCorrection.class);
            correction.setProject(fixture.project); correction.setAddendum(addendum);
            correction = fixture.save(correction);
            if (amount != null) {
                var item = data.create(PaymentScheduleCorrectionItem.class);
                item.setCorrection(correction); item.setItemDate(rowDate); item.setAmount(new BigDecimal(amount));
                fixture.save(item);
            }
            return correction;
        }
        var correction = data.create(ShipmentScheduleCorrection.class);
        correction.setProject(fixture.project); correction.setAddendum(addendum);
        correction = fixture.save(correction);
        if (amount != null) {
            var item = data.create(ShipmentScheduleCorrectionItem.class);
            item.setCorrection(correction); item.setItemDate(rowDate); item.setAmount(new BigDecimal(amount));
            fixture.save(item);
        }
        return correction;
    }

    @io.jmix.security.role.annotation.RowLevelRole(name = "Effective hidden empty correction", code = "effective-hidden-empty-correction")
    public interface HiddenEmptyCorrection {
        @io.jmix.security.role.annotation.JpqlRowLevelPolicy(entityClass = ShipmentScheduleCorrection.class,
                where = "{E}.addendum.number <> 'effective-3'")
        void corrections();
    }
}
