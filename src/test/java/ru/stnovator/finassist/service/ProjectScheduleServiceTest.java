package ru.stnovator.finassist.service;

import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.test_support.RelatedFixtures;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class ProjectScheduleServiceTest {
    @Autowired DataManager data;
    @Autowired SystemAuthenticator auth;
    @Autowired ProjectScheduleService service;
    RelatedFixtures fixtures;
    LocalDate date = LocalDate.of(2026,10,1);
    @BeforeEach void setup() { auth.begin("admin"); fixtures = new RelatedFixtures(data,auth); }
    @AfterEach void cleanup() { fixtures.close(); auth.end(); }
    @Test void combinesByDateAndDistinguishesAbsentFromZero() {
        fixtures.shipment(date,"100"); fixtures.shipment(date,"50"); fixtures.payment(date,"80");
        fixtures.payment(date.plusDays(4),"70"); fixtures.shipment(date.plusDays(5),"0");
        var page = service.schedule(fixtures.project.getId(),null);
        assertThat(page.rows()).hasSize(3);
        assertThat(page.rows().get(0).shipments()).isEqualByComparingTo("150");
        assertThat(page.rows().get(0).payments()).isEqualByComparingTo("80");
        assertThat(page.rows().get(1).shipments()).isNull();
        assertThat(page.rows().get(2).shipments()).isZero();
        assertThat(page.shipmentTotal()).isEqualByComparingTo("150");
        assertThat(page.paymentTotal()).isEqualByComparingTo("150");
    }
    @Test void pagesDistinctDatesAndKeepsWholeScheduleTotals() {
        for(int i=0;i<52;i++) { fixtures.shipment(date.plusDays(i),"10"); fixtures.payment(date.plusDays(i),"2"); }
        fixtures.shipment(date,"-1");
        var first=service.schedule(fixtures.project.getId(),null);
        var second=service.schedule(fixtures.project.getId(),date.plusDays(49));
        assertThat(first.rows()).hasSize(50); assertThat(first.hasNext()).isTrue();
        assertThat(second.rows()).hasSize(2); assertThat(second.hasNext()).isFalse();
        assertThat(second.rows().getFirst().date()).isEqualTo(date.plusDays(50));
        assertThat(second.shipmentTotal()).isEqualByComparingTo("519");
        assertThat(second.paymentTotal()).isEqualByComparingTo("104");
    }
    @Test void emptyAndDeletedItemsDoNotInventDates() {
        var removed=fixtures.shipment(date,"10"); data.remove(removed);
        assertThat(service.schedule(fixtures.project.getId(),null).rows()).isEmpty();
        fixtures.payment(date,"7");
        try(var other=new RelatedFixtures(data,auth)) { other.shipment(date.plusDays(1),"900");
            var page=service.schedule(fixtures.project.getId(),null);
            assertThat(page.rows()).hasSize(1); assertThat(page.shipmentTotal()).isZero();
            assertThat(page.paymentTotal()).isEqualByComparingTo("7");
        }
    }
}
