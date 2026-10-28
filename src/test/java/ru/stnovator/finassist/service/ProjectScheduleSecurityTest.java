package ru.stnovator.finassist.service;

import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.security.model.RowLevelBiPredicate;
import io.jmix.security.model.RowLevelPolicyAction;
import io.jmix.security.role.annotation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.test_support.*;
import java.time.LocalDate;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class ProjectScheduleSecurityTest {
    @Autowired DataManager data;
    @Autowired SystemAuthenticator auth;
    @Autowired ProjectScheduleService service;
    RelatedFixtures fixture;
    LocalDate date = LocalDate.of(2026,10,1);
    @BeforeEach void setup() { auth.begin("admin"); fixture = new RelatedFixtures(data,auth); }
    @AfterEach void cleanup() { fixture.close(); auth.end(); }
    @ParameterizedTest @ValueSource(strings={"related-item-jpql","related-item-predicate"})
    void hiddenItemsNeverContributeDatesOrTotals(String role) {
        fixture.shipment(date,"10"); fixture.shipment(date.plusDays(1),"999"); fixture.payment(date,"5");
        User user=data.create(User.class); user.setUsername("related-security");
        DashboardFixtures.restricted(user,role);
        var page=service.schedule(fixture.project.getId(),null);
        assertThat(page.rows()).hasSize(1);
        assertThat(page.shipmentTotal()).isEqualByComparingTo("10");
        assertThat(page.paymentTotal()).isEqualByComparingTo("5");
    }
    @Test void headerPredicateHidesItsItemsEvenWhenItemsAreReadable() {
        fixture.shipment(date,"100"); fixture.payment(date.plusDays(1),"20");
        User user=data.create(User.class); user.setUsername("related-security");
        DashboardFixtures.restricted(user,"related-header-predicate");
        var page=service.schedule(fixture.project.getId(),null);
        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().getFirst().date()).isEqualTo(date.plusDays(1));
        assertThat(page.shipmentTotal()).isZero();
    }
    @Test void predicatesAcrossBatchBoundaryDoNotSkipOrDuplicateItems() {
        for(int i=0;i<135;i++) fixture.shipment(date.plusDays(i), i%2==0 ? "10" : "999");
        User user=data.create(User.class); user.setUsername("related-security");
        DashboardFixtures.restricted(user,"related-item-predicate");
        var page=service.schedule(fixture.project.getId(),date.plusDays(98));
        assertThat(page.rows()).hasSize(18);
        assertThat(page.rows().getFirst().date()).isEqualTo(date.plusDays(100));
        assertThat(page.shipmentTotal()).isEqualByComparingTo("680");
    }
    @Test void unreadableShipmentAmountMakesWholeSideUnavailable() {
        fixture.shipment(date,"100"); fixture.payment(date.plusDays(1),"20");
        User user=data.create(User.class); user.setUsername("related-attribute-security");
        DashboardFixtures.authenticate(user,"ROLE_related-payment-reader");
        var page=service.schedule(fixture.project.getId(),null);
        assertThat(page.shipmentsAvailable()).isFalse();
        assertThat(page.shipmentTotal()).isNull();
        assertThat(page.paymentsAvailable()).isTrue();
        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().getFirst().date()).isEqualTo(date.plusDays(1));
        assertThat(page.paymentTotal()).isEqualByComparingTo("20");
    }
    @io.jmix.security.role.annotation.ResourceRole(name="Related payment reader",code="related-payment-reader")
    public interface PaymentReader {
        @EntityPolicy(entityClass=Project.class,actions=io.jmix.security.model.EntityPolicyAction.READ)
        @EntityAttributePolicy(entityClass=Project.class,attributes="*",action=io.jmix.security.model.EntityAttributePolicyAction.VIEW)
        void projects();
        @EntityPolicy(entityClass=PaymentSchedule.class,actions=io.jmix.security.model.EntityPolicyAction.READ)
        @EntityAttributePolicy(entityClass=PaymentSchedule.class,attributes="*",action=io.jmix.security.model.EntityAttributePolicyAction.VIEW)
        void payments();
        @EntityPolicy(entityClass=PaymentScheduleItem.class,actions=io.jmix.security.model.EntityPolicyAction.READ)
        @EntityAttributePolicy(entityClass=PaymentScheduleItem.class,attributes="*",action=io.jmix.security.model.EntityAttributePolicyAction.VIEW)
        void paymentItems();
        @EntityPolicy(entityClass=ShipmentSchedule.class,actions=io.jmix.security.model.EntityPolicyAction.READ)
        @EntityAttributePolicy(entityClass=ShipmentSchedule.class,attributes="*",action=io.jmix.security.model.EntityAttributePolicyAction.VIEW)
        void shipments();
        @EntityPolicy(entityClass=ShipmentScheduleItem.class,actions=io.jmix.security.model.EntityPolicyAction.READ)
        @EntityAttributePolicy(entityClass=ShipmentScheduleItem.class,attributes={"schedule","itemDate"},action=io.jmix.security.model.EntityAttributePolicyAction.VIEW)
        void shipmentItems();
    }
    @RowLevelRole(name="Related item JPQL", code="related-item-jpql")
    public interface ItemJpql {
        @JpqlRowLevelPolicy(entityClass=ShipmentScheduleItem.class, where="{E}.amount < 100") void items();
    }
    @RowLevelRole(name="Related item predicate", code="related-item-predicate")
    public interface ItemPredicate {
        @PredicateRowLevelPolicy(entityClass=ShipmentScheduleItem.class, actions=RowLevelPolicyAction.READ)
        default RowLevelBiPredicate<ShipmentScheduleItem,ApplicationContext> items() {
            return (item,ctx) -> item.getAmount().compareTo(new BigDecimal("100"))<0;
        }
    }
    @RowLevelRole(name="Related header predicate", code="related-header-predicate")
    public interface HeaderPredicate {
        @PredicateRowLevelPolicy(entityClass=ShipmentSchedule.class, actions=RowLevelPolicyAction.READ)
        default RowLevelBiPredicate<ShipmentSchedule,ApplicationContext> headers() { return (item,ctx) -> false; }
    }
}
