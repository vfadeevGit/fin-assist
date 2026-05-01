package ru.stnovator.finassist.security;

import io.jmix.core.AccessManager;
import io.jmix.core.Metadata;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.ClientDetails;
import io.jmix.core.security.UserRepository;
import ru.stnovator.finassist.entity.Customer;
import io.jmix.security.model.ResourcePolicyType;
import io.jmix.security.model.ResourceRole;
import io.jmix.security.role.ResourceRoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OpenclawUserRoleTest {

    @Autowired
    AccessManager accessManager;

    @Autowired
    Metadata metadata;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ResourceRoleRepository resourceRoleRepository;

    @AfterEach
    void cleanupAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void customerReadIsPermittedForConfiguredApiRole() {
        ResourceRole role = resourceRoleRepository.findRoleByCode(FullAccessRole.CODE);
        assertThat(role)
                .as("configured API role should be registered")
                .isNotNull();
        assertThat(role.getScopes()).contains("API");
        assertThat(role.getAllResourcePoliciesIndex().getPoliciesByTypeAndResource(ResourcePolicyType.ENTITY, "*"))
                .as("entity policies are %s", role.getAllResourcePolicies().stream()
                        .filter(policy -> ResourcePolicyType.ENTITY.equals(policy.getType()))
                        .map(policy -> policy.getResource() + ":" + policy.getAction())
                        .toList())
                .extracting(io.jmix.security.model.ResourcePolicy::getAction)
                .contains("read");

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userRepository.loadUserByUsername("admin"),
                "n/a",
                java.util.List.of(
                        RoleGrantedAuthorities.resource(FullAccessRole.CODE),
                        RoleGrantedAuthorities.resource("rest-minimal")
                )
        );
        authentication.setDetails(ClientDetails.builder().scope("API").build());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        MetaClass metaClass = metadata.getClass(Customer.class);
        CrudEntityContext accessContext = new CrudEntityContext(metaClass);
        accessManager.applyRegisteredConstraints(accessContext);

        assertThat(accessContext.isReadPermitted()).isTrue();
    }

    private static final class RoleGrantedAuthorities {
        private static org.springframework.security.core.authority.SimpleGrantedAuthority resource(String roleCode) {
            return new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + roleCode);
        }
    }
}
