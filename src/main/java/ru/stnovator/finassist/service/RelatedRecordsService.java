package ru.stnovator.finassist.service;

import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import org.springframework.stereotype.Service;
import ru.stnovator.finassist.entity.*;
import java.util.List;
import java.util.UUID;

@Service
public class RelatedRecordsService {
    public static final int PAGE_SIZE = 50;
    public record Page<T>(List<T> items, boolean hasNext) {
        public Page { items = List.copyOf(items); }
    }
    private final DataManager data;
    private final RelatedDataAccess access;
    public RelatedRecordsService(DataManager data, RelatedDataAccess access) { this.data = data; this.access = access; }

    public Page<Contract> contracts(UUID customerId, int page) {
        access.requireSource(Customer.class, customerId);
        if (!access.canRead(Contract.class, "customer")) return new Page<>(List.of(), false);
        var items = data.load(Contract.class)
                .query("select e from Contract e where e.customer.id = :parent order by e.startDate desc, e.internalID, e.id")
                .parameter("parent", customerId).fetchPlan(FetchPlan.BASE)
                .firstResult(offset(page)).maxResults(PAGE_SIZE + 1).list();
        return page(items);
    }

    public Page<Project> projects(UUID contractId, int page) {
        access.requireSource(Contract.class, contractId);
        if (!access.canRead(Project.class, "contract")) return new Page<>(List.of(), false);
        var items = data.load(Project.class)
                .query("select e from Project e where e.contract.id = :parent order by e.name, e.id")
                .parameter("parent", contractId).fetchPlan(f -> f.addFetchPlan(FetchPlan.BASE).add("lineOfBusiness", FetchPlan.INSTANCE_NAME))
                .firstResult(offset(page)).maxResults(PAGE_SIZE + 1).list();
        return page(items);
    }

    public Contract contract(UUID customerId, UUID contractId) {
        access.requireSource(Customer.class, customerId);
        if (!access.canRead(Contract.class, "customer")) throw new IllegalStateException("Related record unavailable");
        return data.load(Contract.class).query("select e from Contract e where e.id = :id and e.customer.id = :parent")
                .parameter("id", contractId).parameter("parent", customerId).fetchPlan(FetchPlan.BASE).optional()
                .orElseThrow(() -> new IllegalStateException("Related record unavailable"));
    }

    public Project project(UUID contractId, UUID projectId) {
        access.requireSource(Contract.class, contractId);
        if (!access.canRead(Project.class, "contract")) throw new IllegalStateException("Related record unavailable");
        return data.load(Project.class).query("select e from Project e where e.id = :id and e.contract.id = :parent")
                .parameter("id", projectId).parameter("parent", contractId).fetchPlan(FetchPlan.BASE).optional()
                .orElseThrow(() -> new IllegalStateException("Related record unavailable"));
    }

    private <T> Page<T> page(List<T> values) {
        return new Page<>(values.subList(0, Math.min(values.size(), PAGE_SIZE)), values.size() > PAGE_SIZE);
    }

    public static int offset(int page) {
        if (page < 0) throw new IllegalArgumentException("Negative page");
        return Math.multiplyExact(page, PAGE_SIZE);
    }
}
