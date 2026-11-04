package ru.stnovator.finassist.service;

import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.test_support.RelatedFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class CorrectionComparisonServiceTest {
    @Autowired DataManager data;
    @Autowired SystemAuthenticator auth;
    @Autowired CorrectionComparisonService service;
    RelatedFixtures fixtures;
    LocalDate date = LocalDate.of(2026, 6, 1);
    @BeforeEach void setup() { auth.begin("admin"); fixtures = new RelatedFixtures(data, auth); }
    @AfterEach void cleanup() { try { fixtures.close(); } finally { auth.end(); } }
    private Addendum addendum(int month) {
        var a = data.create(Addendum.class); a.setContract(fixtures.contract);
        a.setNumber("comparison-" + month); a.setEffectiveDate(LocalDate.of(2026, month, 1));
        return fixtures.save(a);
    }
    private void shipment(Addendum a, String amount) {
        var c = data.create(ShipmentScheduleCorrection.class); c.setAddendum(a); c.setProject(fixtures.project);
        c = fixtures.save(c);
        if (amount != null) {
            var i = data.create(ShipmentScheduleCorrectionItem.class); i.setCorrection(c);
            i.setItemDate(date); i.setAmount(new BigDecimal(amount)); fixtures.save(i);
        }
    }
    private CorrectionComparisonService.Baselines load(CorrectionComparisonService.Kind kind, Addendum a) {
        return service.load(kind, fixtures.contract.getId(), a.getEffectiveDate(), a.getId(), Set.of(fixtures.project.getId()));
    }
    @Test void latestEarlierNonemptyCorrectionWinsAndTypesAreIndependent() {
        fixtures.shipment(date, "10"); fixtures.payment(date, "30");
        shipment(addendum(2), "20"); shipment(addendum(3), "25");
        shipment(addendum(4), null);
        var current = addendum(6); shipment(current, "999"); shipment(addendum(7), "1000");
        var before = load(CorrectionComparisonService.Kind.SHIPMENT, current);
        assertThat(before.available()).isTrue();
        assertThat(before.schedules().get(fixtures.project.getId())).singleElement()
                .satisfies(e -> assertThat(e.amount()).isEqualByComparingTo("25"));
        assertThat(load(CorrectionComparisonService.Kind.PAYMENT, current).schedules().get(fixtures.project.getId()))
                .singleElement().satisfies(e -> assertThat(e.amount()).isEqualByComparingTo("30"));
    }
    @Test void fallsBackToBaseAndRetainsDatesBeforeEffectiveDate() {
        fixtures.shipment(date.minusMonths(4), "11.12");
        var current = addendum(6);
        assertThat(load(CorrectionComparisonService.Kind.SHIPMENT, current).schedules().get(fixtures.project.getId()))
                .singleElement().satisfies(e -> {
                    assertThat(e.date()).isEqualTo(date.minusMonths(4));
                    assertThat(e.amount()).isEqualByComparingTo("11.12");
                });
    }
    @Test void missingEffectiveDateIsUnavailable() {
        assertThat(service.load(CorrectionComparisonService.Kind.PAYMENT, fixtures.contract.getId(), null,
                java.util.UUID.randomUUID(), Set.of(fixtures.project.getId())).available()).isFalse();
    }
    @Test void hiddenCorrectionItemsMakeComparisonUnavailable() {
        shipment(addendum(2), "999");
        var current = addendum(6);
        var user = data.create(User.class); user.setUsername("comparison-restricted");
        ru.stnovator.finassist.test_support.DashboardFixtures.restricted(user, "comparison-hidden-item");
        assertThat(load(CorrectionComparisonService.Kind.SHIPMENT, current).available()).isFalse();
    }
    @Test void hiddenBaseItemsMakeComparisonUnavailable() {
        fixtures.shipment(date, "999");
        var current = addendum(6);
        var user = data.create(User.class); user.setUsername("comparison-base-restricted");
        ru.stnovator.finassist.test_support.DashboardFixtures.restricted(user, "related-item-jpql");
        assertThat(load(CorrectionComparisonService.Kind.SHIPMENT, current).available()).isFalse();
    }
    @io.jmix.security.role.annotation.RowLevelRole(name="Comparison hidden item", code="comparison-hidden-item")
    public interface HiddenItem {
        @io.jmix.security.role.annotation.JpqlRowLevelPolicy(entityClass=ShipmentScheduleCorrectionItem.class,
                where="{E}.amount < 100") void items();
    }

    @Test void hiddenHistoryHeaderCannotContributeThroughAJoin() {
        var hidden = addendum(2); hidden.setNumber("hidden"); hidden = data.save(hidden);
        shipment(hidden, "999");
        var current = addendum(6);
        var user = data.create(User.class); user.setUsername("comparison-history-restricted");
        ru.stnovator.finassist.test_support.DashboardFixtures.restricted(user, "terms-hidden-history");
        assertThat(load(CorrectionComparisonService.Kind.SHIPMENT, current).available()).isFalse();
    }
    @Test void hiddenScheduleHeaderCannotContributeThroughAJoin() {
        fixtures.shipment(date, "999");
        var current = addendum(6);
        var user = data.create(User.class); user.setUsername("comparison-header-restricted");
        ru.stnovator.finassist.test_support.DashboardFixtures.restricted(user, "related-header-predicate");
        assertThat(load(CorrectionComparisonService.Kind.SHIPMENT, current).available()).isFalse();
    }

}
