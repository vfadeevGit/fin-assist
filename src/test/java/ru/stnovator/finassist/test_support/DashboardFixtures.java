package ru.stnovator.finassist.test_support;

import io.jmix.core.*;
import io.jmix.core.security.ClientDetails;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.security.model.RowLevelBiPredicate;
import io.jmix.security.model.RowLevelPolicyAction;
import io.jmix.data.PersistenceHints;
import io.jmix.security.role.annotation.*;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.stnovator.finassist.entity.*;
import java.time.LocalDate;
import java.util.*;

/** Test-only data and role fixtures; never included in the production artifact. */
public final class DashboardFixtures implements AutoCloseable {
    public static final String JPQL = "dashboard-test-jpql";
    public static final String PREDICATE = "dashboard-test-predicate";
    private final DataManager dataManager;
    private final SystemAuthenticator authenticator;
    private final List<Object> created = new ArrayList<>();
    public final User alice;
    public final User bob;

    public DashboardFixtures(DataManager dataManager, SystemAuthenticator authenticator) {
        this.dataManager = dataManager;
        this.authenticator = authenticator;
        alice = authenticator.withUser("admin", () -> user("da-"));
        bob = authenticator.withUser("admin", () -> user("db-"));
    }

    private User user(String prefix) {
        User user = dataManager.create(User.class);
        user.setUsername(prefix + UUID.randomUUID().toString().substring(0, 8));
        user.setPassword("{noop}dashboard-test");
        return save(user);
    }

    private <T> T save(T value) {
        T saved = dataManager.save(value);
        created.add(saved);
        return saved;
    }

    public void dataset(User owner, boolean deleted) {
        authenticator.runWithUser("admin", () -> {
            String name = owner.getUsername() + "-" + UUID.randomUUID().toString().substring(0, 8);
            Customer customer = dataManager.create(Customer.class);
            customer.setName(name);
            customer = save(customer);
            Contract contract = dataManager.create(Contract.class);
            contract.setInternalID(name);
            contract.setStartDate(LocalDate.of(2020, 1, 1));
            contract.setEndDate(LocalDate.of(2021, 1, 1));
            contract.setCustomer(customer);
            contract = save(contract);
            LineOfBusiness business = dataManager.create(LineOfBusiness.class);
            business.setName(name);
            business = save(business);
            Project project = dataManager.create(Project.class);
            project.setName(name);
            project.setContract(contract);
            project.setLineOfBusiness(business);
            project = save(project);
            if (deleted) {
                dataManager.remove(project);
                dataManager.remove(contract);
                dataManager.remove(customer);
            }
        });
    }

    public static void authenticate(User user, String... authorities) {
        var granted = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        var token = UsernamePasswordAuthenticationToken.authenticated(user, null, granted);
        token.setDetails(ClientDetails.builder().scope("UI").build());
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    public static void restricted(User user, String rowRole) {
        authenticate(user, "ROLE_system-full-access", "ROW_LEVEL_ROLE_" + rowRole);
    }

    @Override
    public void close() {
        authenticator.runWithUser("admin", () -> {
            Collections.reverse(created);
            for (Object entity : created) {
                dataManager.load(Id.of(entity)).hint(PersistenceHints.SOFT_DELETION, false).optional()
                        .ifPresent(current -> dataManager.save(new SaveContext().removing(current)
                                .setHint(PersistenceHints.SOFT_DELETION, false)));
            }
        });
    }

    @RowLevelRole(name = "Dashboard test JPQL", code = JPQL)
    public interface OwnRecords {
        @JpqlRowLevelPolicy(entityClass = Customer.class, where = "{E}.name like concat(:current_user_username, '%')")
        void customers();
        @JpqlRowLevelPolicy(entityClass = Contract.class, where = "{E}.internalID like concat(:current_user_username, '%')")
        void contracts();
        @JpqlRowLevelPolicy(entityClass = Project.class, where = "{E}.name like concat(:current_user_username, '%')")
        void projects();
    }

    @RowLevelRole(name = "Dashboard test predicate", code = PREDICATE)
    public interface OwnRecordsPredicate {
        @PredicateRowLevelPolicy(entityClass = Customer.class, actions = RowLevelPolicyAction.READ)
        default RowLevelBiPredicate<Customer, ApplicationContext> customers() {
            return (entity, ctx) -> entity.getName().startsWith(ctx.getBean(CurrentAuthentication.class).getUser().getUsername());
        }
        @PredicateRowLevelPolicy(entityClass = Contract.class, actions = RowLevelPolicyAction.READ)
        default RowLevelBiPredicate<Contract, ApplicationContext> contracts() {
            return (entity, ctx) -> entity.getInternalID().startsWith(ctx.getBean(CurrentAuthentication.class).getUser().getUsername());
        }
        @PredicateRowLevelPolicy(entityClass = Project.class, actions = RowLevelPolicyAction.READ)
        default RowLevelBiPredicate<Project, ApplicationContext> projects() {
            return (entity, ctx) -> entity.getName().startsWith(ctx.getBean(CurrentAuthentication.class).getUser().getUsername());
        }
    }
}
