package ru.stnovator.finassist.report;

import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.reports.annotation.*;
import io.jmix.reports.entity.DataSetType;
import io.jmix.reports.entity.ReportOutputType;
import io.jmix.reports.yarg.loaders.ReportDataLoader;
import io.jmix.reportsflowui.role.ReportsRunRole;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.entity.Customer;
import ru.stnovator.finassist.entity.PaymentScheduleItem;
import ru.stnovator.finassist.entity.Project;
import ru.stnovator.finassist.entity.ShipmentScheduleItem;
import ru.stnovator.finassist.view.contract.ContractListView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
@BandDef(
        name = "Root",
        root = true
)
@BandDef(
        name = "Header",
        parent = "Root"
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
    private final ThreadLocal<Map<UUID, CashflowComputation>> cashflowComputations =
            ThreadLocal.withInitial(HashMap::new);
    private final DataManager dataManager;
    private final MetadataTools metadataTools;
    private final Messages messages;

    public ListOfContracts(DataManager dataManager, MetadataTools metadataTools, Messages messages) {
        this.dataManager = dataManager;
        this.metadataTools = metadataTools;
        this.messages = messages;
    }

    @DataSetDelegate(name = "customers")
    public ReportDataLoader customersDataLoader() {
        return (reportQuery, parentBand, params) -> {
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
            List<Contract> contracts = dataManager.load(Contract.class)
                    .query("""
                            select c from Contract c
                            where c.customer.id = :customerId
                            order by c.startDate desc, c.internalID
                            """)
                    .parameter("customerId", parentBand.getData().get("customerId"))
                    .list();

            return contracts.stream()
                    .map(contract -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("contractId", contract.getId());
                        map.put("contractInternalID", contract.getInternalID());
                        map.put("contractStartDate", contract.getStartDate());
                        map.put("contractEndDate", contract.getEndDate());
                        map.put("paymentType", contract.getPaymentType() == null ? "" : messages.getMessage(contract.getPaymentType()));
                        map.put("totalAmount", contract.getTotalAmount());
                        return map;
                    })
                    .toList();
        };
    }

    @DataSetDelegate(name = "projects")
    public ReportDataLoader projectsDataLoader() {
        return (reportQuery, parentBand, params) -> {
            List<Project> projects = dataManager.load(Project.class)
                    .query("""
                            select p from Project p
                            left join fetch p.lineOfBusiness
                            where p.contract.id = :contractId
                            order by p.name
                            """)
                    .parameter("contractId", parentBand.getData().get("contractId"))
                    .list();

            return projects.stream()
                    .map(this::createProjectRow)
                    .toList();
        };
    }

    @DataSetDelegate(name = "projectCashflowRecords")
    public ReportDataLoader projectCashflowRecordsDataLoader() {
        return (reportQuery, parentBand, params) -> {
            UUID projectId = (UUID) parentBand.getData().get("projectId");
            CashflowComputation computation = computeCashflow(projectId);
            cashflowComputations.get().put(projectId, computation);
            return computation.rows();
        };
    }

    @DataSetDelegate(name = "projectCashflowTotals")
    public ReportDataLoader projectCashflowTotalsDataLoader() {
        return (reportQuery, parentBand, params) -> {
            UUID projectId = (UUID) parentBand.getData().get("projectId");
            CashflowComputation computation = cashflowComputations.get().computeIfAbsent(projectId, this::computeCashflow);

            Map<String, Object> map = new HashMap<>();
            map.put("shipmentTotal", computation.shipmentTotal());
            map.put("paymentTotal", computation.paymentTotal());
            map.put("finalDelta", computation.finalDelta());

            cashflowComputations.get().remove(projectId);
            return List.of(map);
        };
    }

    private Map<String, Object> createProjectRow(Project project) {
        Map<String, Object> map = new HashMap<>();
        map.put("projectId", project.getId());
        map.put("projectName", project.getName());
        map.put("lineOfBusiness", project.getLineOfBusiness() == null
                ? ""
                : metadataTools.format(project.getLineOfBusiness()));
        return map;
    }

    private CashflowComputation computeCashflow(UUID projectId) {
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

        TreeMap<LocalDate, CashflowDay> days = new TreeMap<>();
        BigDecimal shipmentTotal = BigDecimal.ZERO;
        BigDecimal paymentTotal = BigDecimal.ZERO;

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

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal delta = BigDecimal.ZERO;
        for (Map.Entry<LocalDate, CashflowDay> entry : days.entrySet()) {
            CashflowDay day = entry.getValue();
            delta = delta.add(day.shipmentSum).subtract(day.paymentSum);

            Map<String, Object> row = new HashMap<>();
            row.put("cashflowDate", entry.getKey());
            row.put("shipmentSum", day.shipmentSum);
            row.put("paymentSum", day.paymentSum);
            row.put("deltaSum", delta);
            rows.add(row);
        }

        return new CashflowComputation(rows, shipmentTotal, paymentTotal, delta);
    }

    private static class CashflowDay {
        private BigDecimal shipmentSum = BigDecimal.ZERO;
        private BigDecimal paymentSum = BigDecimal.ZERO;
    }

    private record CashflowComputation(
            List<Map<String, Object>> rows,
            BigDecimal shipmentTotal,
            BigDecimal paymentTotal,
            BigDecimal finalDelta
    ) {
    }
}
