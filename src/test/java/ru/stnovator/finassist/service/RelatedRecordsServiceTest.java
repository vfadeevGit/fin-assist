package ru.stnovator.finassist.service;

import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.test_support.RelatedFixtures;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class RelatedRecordsServiceTest {
    @Autowired DataManager data;
    @Autowired SystemAuthenticator auth;
    @Autowired RelatedRecordsService service;
    RelatedFixtures fixtures;
    @BeforeEach void setup() { auth.begin("admin"); fixtures = new RelatedFixtures(data,auth); }
    @AfterEach void cleanup() { fixtures.close(); auth.end(); }
    @Test void loadsOnlyChildrenOfTheRequestedSource() {
        try (var other = new RelatedFixtures(data,auth)) {
            assertThat(service.contracts(fixtures.customer.getId(),0).items()).extracting("id").containsExactly(fixtures.contract.getId());
            assertThat(service.projects(fixtures.contract.getId(),0).items()).extracting("id").containsExactly(fixtures.project.getId());
        }
    }
    @Test void staleChildLinkCannotOpenAfterReassignment() {
        try (var other = new RelatedFixtures(data,auth)) {
            fixtures.project.setContract(other.contract); data.save(fixtures.project);
            assertThatThrownBy(() -> service.project(fixtures.contract.getId(), fixtures.project.getId()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void deletedSourceCannotRevealChildren() {
        data.remove(fixtures.project);
        assertThat(service.projects(fixtures.contract.getId(),0).items()).isEmpty();
        assertThatThrownBy(() -> service.contracts(java.util.UUID.randomUUID(),0)).isInstanceOf(IllegalStateException.class);
    }
}
