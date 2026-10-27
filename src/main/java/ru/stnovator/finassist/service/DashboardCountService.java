package ru.stnovator.finassist.service;

import io.jmix.core.DataManager;
import io.jmix.core.LoadContext;
import io.jmix.core.Metadata;
import org.springframework.stereotype.Service;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.entity.Customer;
import ru.stnovator.finassist.entity.Project;

/** Counts records under the caller's security context, including row-level predicates. */
@Service
public class DashboardCountService {
    private final DataManager dataManager;
    private final Metadata metadata;

    public DashboardCountService(DataManager dataManager, Metadata metadata) {
        this.dataManager = dataManager;
        this.metadata = metadata;
    }

    public long countCustomers() {
        return count(Customer.class);
    }

    public long countContracts() {
        return count(Contract.class);
    }

    public long countProjects() {
        return count(Project.class);
    }

    private long count(Class<?> entityClass) {
        var metaClass = metadata.getClass(entityClass);
        LoadContext<?> context = new LoadContext<>(metaClass);
        context.setQuery(new LoadContext.Query("select e from " + metaClass.getName() + " e"));
        // Keep the default soft-deletion and entity-loading path for predicate policies.
        context.setJoinTransaction(false);
        return dataManager.getCount(context);
    }
}
