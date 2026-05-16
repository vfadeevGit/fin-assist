package ru.stnovator.finassist.report;

import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.reports.annotation.*;
import io.jmix.reports.entity.DataSetType;
import io.jmix.reports.entity.ParameterType;
import io.jmix.reports.entity.ReportOutputType;
import io.jmix.reports.yarg.loaders.ReportDataLoader;
import io.jmix.reportsflowui.role.ReportsRunRole;
import ru.stnovator.finassist.entity.Addendum;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.entity.Customer;
import ru.stnovator.finassist.entity.PaymentScheduleCorrectionItem;
import ru.stnovator.finassist.entity.PaymentScheduleItem;
import ru.stnovator.finassist.entity.Project;
import ru.stnovator.finassist.entity.ShipmentScheduleCorrectionItem;
import ru.stnovator.finassist.entity.ShipmentScheduleItem;
import ru.stnovator.finassist.view.contract.ContractListView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@ReportDef(
        code = "list-of-contracts",
        name = "List Of Contracts",
        description = "The list of contracts, groupped by customers, projects and full details about planned shipment and payment",
        uuid = "0ce06fdf-ee93-4bce-9b25-03cae4ae2ab2",
        beanName = "ListOfContractsService",
        restAccessible = true
)
@AvailableForRoles(roleClasses = ReportsRunRole.class)
@AvailableInViews(viewClasses = ContractListView.class)
@TemplateDef(
        isDefault = true,
        code = "DEFAULT",
        filePath = "ru/stnovator/finassist/report/list-of-contracts.xlsx",
        outputType = ReportOutputType.XLSX,
        outputNamePattern = "list-of-contracts.xlsx"
)
@InputParameterDef(
        alias = "reportDate",
        name = "msg://ru.stnovator.finassist.report.listOfContracts/reportDate",
        type = ParameterType.DATE,
        defaultDateIsCurrent = true,
        required = true
)
@InputParameterDef(
        alias = "customer",
        name = "msg://ru.stnovator.finassist.entity/Customer",
        type = ParameterType.ENTITY,
        entity = @EntityParameterDef(entityClass = Customer.class)
)
@InputParameterDef(
        alias = "contract",
        name = "msg://ru.stnovator.finassist.entity/Contract",
        type = ParameterType.ENTITY,
        entity = @EntityParameterDef(entityClass = Contract.class)
)
@InputParameterDef(
        alias = "project",
        name = "msg://ru.stnovator.finassist.entity/Project",
        type = ParameterType.ENTITY,
        entity = @EntityParameterDef(entityClass = Project.class)
)
@InputParameterDef(
        alias = "includeAddendum",
        name = "msg://ru.stnovator.finassist.report.listOfContracts/includeAddendum",
        type = ParameterType.BOOLEAN
)
@BandDef(
        name = "Root",
        root = true
)
@BandDef(
        name = "Header",
        parent = "Root",
        dataSets = @DataSetDef(
                name = "header",
                type = DataSetType.DELEGATE
        )
)
@BandDef(
        name = "Customer",
        parent = "Header",
        dataSets = @DataSetDef(
                name = "customers",
                type = DataSetType.DELEGATE
        )
)
@BandDef(
        name = "Contract",
        parent = "Customer",
        dataSets = @DataSetDef(
                name = "contracts",
                type = DataSetType.DELEGATE
        )
)
@BandDef(
        name = "Project",
        parent = "Contract",
        dataSets = @DataSetDef(
                name = "projects",
                type = DataSetType.DELEGATE
        )
)
@BandDef(
        name = "ProjectCashflowHeader",
        parent = "Project"
)
@BandDef(
        name = "ProjectCashflowRecord",
        parent = "Project",
        dataSets = @DataSetDef(
                name = "projectCashflowRecords",
                type = DataSetType.DELEGATE
        )
)
@BandDef(
        name = "ProjectCashflowTotal",
        parent = "Project",
        dataSets = @DataSetDef(
                name = "projectCashflowTotals",
                type = DataSetType.DELEGATE
        )
)
@BandDef(
        name = "ProjectSpacer",
        parent = "Project"
)
@BandDef(
        name = "ContractSpacer",
        parent = "Contract"
)
@BandDef(
        name = "CustomerSpacer",
        parent = "Customer"
)
public class ListOfContracts {
    private final ThreadLocal<Map<CashflowKey, CashflowComputation>> cashflowComputations =
            ThreadLocal.withInitial(HashMap::new);
    private final DataManager dataManager;
    private final MetadataTools metadataTools;
    private final Messages messages;

    public ListOfContracts(DataManager dataManager, MetadataTools metadataTools, Messages messages) {
        this.dataManager = dataManager;
        this.metadataTools = metadataTools;
        this.messages = messages;
    }

    @DataSetDelegate(name = "header")
    public ReportDataLoader headerDataLoader() {
        return (reportQuery, parentBand, params) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("reportDate", params.get("reportDate"));
            return List.of(map);
        };
    }

    @DataSetDelegate(name = "customers")
    public ReportDataLoader customersDataLoader() {
        return (reportQuery, parentBand, params) -> {
            Customer selectedCustomer = getEntityParameter(params, "customer", Customer.class);
            Contract selectedContract = getEntityParameter(params, "contract", Contract.class);
            Project selectedProject = getEntityParameter(params, "project", Project.class);

            if (selectedProject != null) {
                Project project = loadProjectForFiltering(selectedProject.getId());
                if (project == null || !matchesSelectedContract(project, selectedContract)
                        || !matchesSelectedCustomer(project.getContract().getCustomer(), selectedCustomer)) {
                    return List.of();
                }
                return List.of(createCustomerRow(project.getContract().getCustomer()));
            }

            if (selectedContract != null) {
                Contract contract = loadContractForFiltering(selectedContract.getId());
                if (contract == null || !matchesSelectedCustomer(contract.getCustomer(), selectedCustomer)) {
                    return List.of();
                }
                return List.of(createCustomerRow(contract.getCustomer()));
            }

            if (selectedCustomer != null) {
                return List.of(createCustomerRow(selectedCustomer));
            }

            List<Customer> customers = dataManager.load(Customer.class)
                    .query("""
                            select distinct c.customer from Contract c
                            where c.customer is not null
                            order by c.customer.name
                            """)
                    .list();
            return customers.stream()
                    .map(customer -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("customerId", customer.getId());
                        map.put("customerName", customer.getName());
                        return map;
                    })
                    .toList();
        };
    }

    @DataSetDelegate(name = "contracts")
    public ReportDataLoader contractsDataLoader() {
        return (reportQuery, parentBand, params) -> {
            Contract selectedContract = getEntityParameter(params, "contract", Contract.class);
            Project selectedProject = getEntityParameter(params, "project", Project.class);
            UUID customerId = (UUID) parentBand.getData().get("customerId");

            if (selectedProject != null) {
                Project project = loadProjectForFiltering(selectedProject.getId());
                if (project == null || !project.getContract().getCustomer().getId().equals(customerId)) {
                    return List.of();
                }
                return List.of(createContractRow(project.getContract(), params));
            }

            if (selectedContract != null) {
                Contract contract = loadContractForFiltering(selectedContract.getId());
                if (contract == null || !contract.getCustomer().getId().equals(customerId)) {
                    return List.of();
                }
                return List.of(createContractRow(contract, params));
            }

            List<Contract> contracts = dataManager.load(Contract.class)
                    .query("""
                            select c from Contract c
                            where c.customer.id = :customerId
                            order by c.startDate desc, c.internalID
                            """)
                    .parameter("customerId", customerId)
                    .list();

            return contracts.stream()
                    .map(contract -> createContractRow(contract, params))
                    .toList();
        };
    }

    @DataSetDelegate(name = "projects")
    public ReportDataLoader projectsDataLoader() {
        return (reportQuery, parentBand, params) -> {
            Project selectedProject = getEntityParameter(params, "project", Project.class);
            UUID contractId = (UUID) parentBand.getData().get("contractId");

            if (selectedProject != null) {
                Project project = loadProjectForFiltering(selectedProject.getId());
                if (project == null || !project.getContract().getId().equals(contractId)) {
                    return List.of();
                }
                return List.of(createProjectRow(project, (UUID) parentBand.getData().get("activeAddendumId")));
            }

            List<Project> projects = dataManager.load(Project.class)
                    .query("""
                            select p from Project p
                            left join fetch p.lineOfBusiness
                            where p.contract.id = :contractId
                            order by p.name
                            """)
                    .parameter("contractId", contractId)
                    .list();

            return projects.stream()
                    .map(project -> createProjectRow(project, (UUID) parentBand.getData().get("activeAddendumId")))
                    .toList();
        };
    }

    @DataSetDelegate(name = "projectCashflowRecords")
    public ReportDataLoader projectCashflowRecordsDataLoader() {
        return (reportQuery, parentBand, params) -> {
            UUID projectId = (UUID) parentBand.getData().get("projectId");
            UUID addendumId = (UUID) parentBand.getData().get("activeAddendumId");
            CashflowKey key = new CashflowKey(projectId, addendumId);
            CashflowComputation computation = computeCashflow(projectId, addendumId);
            cashflowComputations.get().put(key, computation);
            return computation.rows();
        };
    }

    @DataSetDelegate(name = "projectCashflowTotals")
    public ReportDataLoader projectCashflowTotalsDataLoader() {
        return (reportQuery, parentBand, params) -> {
            UUID projectId = (UUID) parentBand.getData().get("projectId");
            UUID addendumId = (UUID) parentBand.getData().get("activeAddendumId");
            CashflowKey key = new CashflowKey(projectId, addendumId);
            CashflowComputation computation = cashflowComputations.get()
                    .computeIfAbsent(key, ignored -> computeCashflow(projectId, addendumId));

            Map<String, Object> map = new HashMap<>();
            map.put("shipmentTotal", computation.shipmentTotal());
            map.put("paymentTotal", computation.paymentTotal());

            cashflowComputations.get().remove(key);
            return List.of(map);
        };
    }

    private Map<String, Object> createProjectRow(Project project, UUID activeAddendumId) {
        Map<String, Object> map = new HashMap<>();
        map.put("projectId", project.getId());
        map.put("activeAddendumId", activeAddendumId);
        map.put("projectName", project.getName());
        map.put("lineOfBusiness", project.getLineOfBusiness() == null
                ? ""
                : metadataTools.format(project.getLineOfBusiness()));
        return map;
    }

    private Map<String, Object> createCustomerRow(Customer customer) {
        Map<String, Object> map = new HashMap<>();
        map.put("customerId", customer.getId());
        map.put("customerName", customer.getName());
        return map;
    }

    private Map<String, Object> createContractRow(Contract contract, Map<String, Object> params) {
        Addendum activeAddendum = resolveActiveAddendum(contract, params);

        Map<String, Object> map = new HashMap<>();
        map.put("contractId", contract.getId());
        map.put("contractInternalID", contract.getInternalID());
        map.put("contractStartDate", contract.getStartDate());
        map.put("contractEndDate", contract.getEndDate());
        map.put("paymentType", contract.getPaymentType() == null ? "" : messages.getMessage(contract.getPaymentType()));
        map.put("totalAmount", contract.getTotalAmount());
        map.put("activeAddendumId", activeAddendum == null ? null : activeAddendum.getId());
        map.put("addendumInfoLabel", activeAddendum == null
                ? ""
                : messages.getMessage("ru.stnovator.finassist.report.listOfContracts/addendumLabel"));
        map.put("addendumNumber", activeAddendum == null ? "" : activeAddendum.getNumber());
        map.put("addendumDateLabel", activeAddendum == null
                ? ""
                : messages.getMessage("ru.stnovator.finassist.report.listOfContracts/addendumDateLabel"));
        map.put("addendumEffectiveDate", activeAddendum == null ? null : activeAddendum.getEffectiveDate());
        return map;
    }

    private Contract loadContractForFiltering(UUID contractId) {
        return dataManager.load(Contract.class)
                .query("""
                        select c from Contract c
                        join fetch c.customer
                        where c.id = :contractId
                        """)
                .parameter("contractId", contractId)
                .optional()
                .orElse(null);
    }

    private Project loadProjectForFiltering(UUID projectId) {
        return dataManager.load(Project.class)
                .query("""
                        select p from Project p
                        join fetch p.contract
                        join fetch p.contract.customer
                        left join fetch p.lineOfBusiness
                        where p.id = :projectId
                        """)
                .parameter("projectId", projectId)
                .optional()
                .orElse(null);
    }

    private boolean matchesSelectedCustomer(Customer actualCustomer, Customer selectedCustomer) {
        return selectedCustomer == null || actualCustomer.getId().equals(selectedCustomer.getId());
    }

    private boolean matchesSelectedContract(Project project, Contract selectedContract) {
        return selectedContract == null || project.getContract().getId().equals(selectedContract.getId());
    }

    private <T> T getEntityParameter(Map<String, Object> params, String alias, Class<T> entityClass) {
        Object value = params.get(alias);
        if (entityClass.isInstance(value)) {
            return entityClass.cast(value);
        }
        return null;
    }

    private CashflowComputation computeCashflow(UUID projectId, UUID addendumId) {
        TreeMap<LocalDate, CashflowDay> days = new TreeMap<>();
        BigDecimal shipmentTotal = BigDecimal.ZERO;
        BigDecimal paymentTotal = BigDecimal.ZERO;

        if (addendumId == null) {
            List<ShipmentScheduleItem> shipmentItems = dataManager.load(ShipmentScheduleItem.class)
                    .query("""
                            select s from ShipmentScheduleItem s
                            where s.schedule.project.id = :projectId
                            order by s.itemDate
                            """)
                    .parameter("projectId", projectId)
                    .list();

            List<PaymentScheduleItem> paymentItems = dataManager.load(PaymentScheduleItem.class)
                    .query("""
                            select p from PaymentScheduleItem p
                            where p.schedule.project.id = :projectId
                            order by p.itemDate
                            """)
                    .parameter("projectId", projectId)
                    .list();

            for (ShipmentScheduleItem item : shipmentItems) {
                CashflowDay day = days.computeIfAbsent(item.getItemDate(), ignored -> new CashflowDay());
                day.shipmentSum = day.shipmentSum.add(item.getAmount());
                shipmentTotal = shipmentTotal.add(item.getAmount());
            }

            for (PaymentScheduleItem item : paymentItems) {
                CashflowDay day = days.computeIfAbsent(item.getItemDate(), ignored -> new CashflowDay());
                day.paymentSum = day.paymentSum.add(item.getAmount());
                paymentTotal = paymentTotal.add(item.getAmount());
            }
        } else {
            List<ShipmentScheduleCorrectionItem> shipmentItems = dataManager.load(ShipmentScheduleCorrectionItem.class)
                    .query("""
                            select s from ShipmentScheduleCorrectionItem s
                            where s.correction.project.id = :projectId
                              and s.correction.addendum.id = :addendumId
                            order by s.itemDate
                            """)
                    .parameter("projectId", projectId)
                    .parameter("addendumId", addendumId)
                    .list();

            List<PaymentScheduleCorrectionItem> paymentItems = dataManager.load(PaymentScheduleCorrectionItem.class)
                    .query("""
                            select p from PaymentScheduleCorrectionItem p
                            where p.correction.project.id = :projectId
                              and p.correction.addendum.id = :addendumId
                            order by p.itemDate
                            """)
                    .parameter("projectId", projectId)
                    .parameter("addendumId", addendumId)
                    .list();

            for (ShipmentScheduleCorrectionItem item : shipmentItems) {
                CashflowDay day = days.computeIfAbsent(item.getItemDate(), ignored -> new CashflowDay());
                day.shipmentSum = day.shipmentSum.add(item.getAmount());
                shipmentTotal = shipmentTotal.add(item.getAmount());
            }

            for (PaymentScheduleCorrectionItem item : paymentItems) {
                CashflowDay day = days.computeIfAbsent(item.getItemDate(), ignored -> new CashflowDay());
                day.paymentSum = day.paymentSum.add(item.getAmount());
                paymentTotal = paymentTotal.add(item.getAmount());
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<LocalDate, CashflowDay> entry : days.entrySet()) {
            CashflowDay day = entry.getValue();

            Map<String, Object> row = new HashMap<>();
            row.put("cashflowDate", entry.getKey());
            row.put("shipmentSum", day.shipmentSum);
            row.put("paymentSum", day.paymentSum);
            rows.add(row);
        }

        return new CashflowComputation(rows, shipmentTotal, paymentTotal);
    }

    private Addendum resolveActiveAddendum(Contract contract, Map<String, Object> params) {
        if (!Boolean.TRUE.equals(params.get("includeAddendum"))) {
            return null;
        }

        LocalDate reportDate = getReportDate(params);
        if (reportDate == null) {
            return null;
        }

        return dataManager.load(Addendum.class)
                .query("""
                        select a from Addendum a
                        where a.contract.id = :contractId
                          and a.effectiveDate <= :reportDate
                        order by a.effectiveDate desc, a.createdDate desc, a.number desc
                        """)
                .parameter("contractId", contract.getId())
                .parameter("reportDate", reportDate)
                .maxResults(1)
                .optional()
                .orElse(null);
    }

    private LocalDate getReportDate(Map<String, Object> params) {
        Object value = params.get("reportDate");
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date date) {
            return date.toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
        }
        return null;
    }

    private static class CashflowDay {
        private BigDecimal shipmentSum = BigDecimal.ZERO;
        private BigDecimal paymentSum = BigDecimal.ZERO;
    }

    private record CashflowKey(UUID projectId, UUID addendumId) {
    }

    private record CashflowComputation(
            List<Map<String, Object>> rows,
            BigDecimal shipmentTotal,
            BigDecimal paymentTotal
    ) {
    }
}
