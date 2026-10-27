package ru.stnovator.finassist.service;

import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.stnovator.finassist.test_support.DashboardFixtures;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class DashboardCountServiceTest {
    @Autowired DataManager dataManager;
    @Autowired SystemAuthenticator authenticator;
    @Autowired DashboardCountService service;
    DashboardFixtures fixtures;

    @BeforeEach void setup() { fixtures = new DashboardFixtures(dataManager, authenticator); }
    @AfterEach void cleanup() { fixtures.close(); SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @ValueSource(strings = {DashboardFixtures.JPQL, DashboardFixtures.PREDICATE})
    void countsAccessibleUndeletedRecords(String policy) {
        fixtures.dataset(fixtures.alice, false);
        fixtures.dataset(fixtures.alice, false);
        fixtures.dataset(fixtures.bob, false);
        fixtures.dataset(fixtures.alice, true);
        DashboardFixtures.restricted(fixtures.alice, policy);
        assertThat(service.countCustomers()).isEqualTo(2L);
        assertThat(service.countContracts()).isEqualTo(2L);
        assertThat(service.countProjects()).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(strings = {DashboardFixtures.JPQL, DashboardFixtures.PREDICATE})
    void returnsZeroForEmptyCollection(String policy) {
        fixtures.dataset(fixtures.bob, false);
        DashboardFixtures.restricted(fixtures.alice, policy);
        assertThat(service.countCustomers()).isZero();
        assertThat(service.countContracts()).isZero();
        assertThat(service.countProjects()).isZero();
    }

    @Test void doesNotElevateAuthentication() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(service::countCustomers).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(service::countContracts).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(service::countProjects).isInstanceOf(IllegalStateException.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
