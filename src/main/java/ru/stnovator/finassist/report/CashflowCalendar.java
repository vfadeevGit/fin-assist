package ru.stnovator.finassist.report;

import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.reports.annotation.AvailableForRoles;
import io.jmix.reports.annotation.AvailableInViews;
import io.jmix.reports.annotation.BandDef;
import io.jmix.reports.annotation.DataSetDef;
import io.jmix.reports.annotation.DataSetDelegate;
import io.jmix.reports.annotation.EntityParameterDef;
import io.jmix.reports.annotation.InputParameterDef;
import io.jmix.reports.annotation.ReportDef;
import io.jmix.reports.annotation.TemplateDef;
import io.jmix.reports.entity.DataSetType;
import io.jmix.reports.entity.Orientation;
import io.jmix.reports.entity.ParameterType;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@ReportDef(
        code = "cashflow-calendar",
        name = "Cashflow Calendar",
        description = "Cross-tab calendar of planned shipment and planned payment values by project and month",
        uuid = "0f4c2f6a-2b67-4a90-99ca-0569a0f5d6bd",
        beanName = "CashflowCalendarService",
        restAccessible = true
)
@AvailableForRoles(roleClasses = ReportsRunRole.class)
@AvailableInViews(viewClasses = ContractListView.class)
@TemplateDef(
        isDefault = true,
        code = "DEFAULT",
        filePath = "ru/stnovator/finassist/report/cashflow-calendar.xlsx",
        outputType = ReportOutputType.XLSX,
        outputNamePattern = "cashflow-calendar.xlsx"
)
@InputParameterDef(
        alias = "reportDate",
        name = "msg://ru.stnovator.finassist.report.cashflowCalendar/reportDate",
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
        name = "msg://ru.stnovator.finassist.report.cashflowCalendar/includeAddendum",
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
        name = "Calendar",
        parent = "Root",
        orientation = Orientation.CROSS,
        dataSets = {
                @DataSetDef(
                        name = "Calendar_dynamic_header",
                        type = DataSetType.DELEGATE
                ),
                @DataSetDef(
                        name = "Calendar_master_data",
                        type = DataSetType.DELEGATE
                ),
                @DataSetDef(
                        name = "Calendar",
                        type = DataSetType.DELEGATE
                )
        }
)
@BandDef(
        name = "CalendarTotals",
        parent = "Root",
        dataSets = @DataSetDef(
                name = "calendarTotals",
                type = DataSetType.DELEGATE
        )
)
public class CashflowCalendar {
    private static final Locale REPORT_LOCALE = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("LLL yyyy", REPORT_LOCALE);
    private static final String SHIPMENT_MEASURE = "SHIPMENT";
    private static final String PAYMENT_MEASURE = "PAYMENT";
    private static final String PROJECT_ROW_KIND = "PROJECT";

    private final DataManager dataManager;
    private final Messages messages;

    public CashflowCalendar(DataManager dataManager, Messages messages) {
        this.dataManager = dataManager;
        this.messages = messages;
    }

    @DataSetDelegate(name = "header")
    public ReportDataLoader headerDataLoader() {
        return (reportQuery, parentBand, params) -> {
            LocalDate reportDate = getRequiredReportDate(params);

            Map<String, Object> row = new HashMap<>();
            row.put("title", messages.getMessage("ru.stnovator.finassist.report.cashflowCalendar/title"));
            row.put("reportDateLabel", messages.getMessage("ru.stnovator.finassist.report.cashflowCalendar/reportDate"));
            row.put("reportDate", reportDate);
            row.put("reportYear", reportDate.getYear());
            return List.of(row);
        };
    }

    @DataSetDelegate(name = "Calendar_dynamic_header")
    public ReportDataLoader calendarDynamicHeaderDataLoader() {
        return (reportQuery, parentBand, params) -> {
            LocalDate reportDate = getRequiredReportDate(params);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int month = 1; month <= 12; month++) {
                YearMonth yearMonth = YearMonth.of(reportDate.getYear(), month);
                rows.add(createHeaderRow(
                        formatMonthId(yearMonth),
                        MONTH_FORMATTER.format(yearMonth.atDay(1)),
                        month
                ));
            }
            return rows;
        };
    }

    @DataSetDelegate(name = "Calendar_master_data")
    public ReportDataLoader calendarMasterDataLoader() {
        return (reportQuery, parentBand, params) -> {
            CalendarContext context = buildContext(params);
            return buildMasterRows(context);
        };
    }

    @DataSetDelegate(name = "calendarTotals")
    public ReportDataLoader calendarTotalsDataLoader() {
        return (reportQuery, parentBand, params) -> {
            CalendarContext context = buildContext(params);
            return buildTotalsRows(context);
        };
    }

    @DataSetDelegate(name = "Calendar")
    public ReportDataLoader calendarDataLoader() {
        return (reportQuery, parentBand, params) -> {
            List<Map<String, Object>> headerRows = getDatasetRows(params, "Calendar_dynamic_header");
            List<Map<String, Object>> masterRows = getDatasetRows(params, "Calendar_master_data");
            if (headerRows.isEmpty() || masterRows.isEmpty()) {
                return List.of();
            }

            CalendarContext context = buildContext(params);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> masterRow : masterRows) {
                String measureCode = (String) masterRow.get("measureCode");
                UUID projectId = (UUID) masterRow.get("projectId");
                String rowId = (String) masterRow.get("rowId");
                MeasureSummary summary = context.projectSummaries()
                        .getOrDefault(new MeasureProjectKey(projectId, measureCode), MeasureSummary.empty());

                for (Map<String, Object> headerRow : headerRows) {
                    String monthId = (String) headerRow.get("monthId");
                    int month = extractMonth(monthId);
                    BigDecimal amount = summary.monthAmount(month);

                    Map<String, Object> cell = new HashMap<>();
                    cell.put("Calendar_dynamic_header@monthId", monthId);
                    cell.put("Calendar_master_data@rowId", rowId);
                    cell.put("amount", amount);
                    result.add(cell);
                }
            }
            return result;
        };
    }

    private CalendarContext buildContext(Map<String, Object> params) {
        LocalDate reportDate = getRequiredReportDate(params);
        List<ProjectContext> visibleProjects = loadVisibleProjects(params);
        Map<MeasureProjectKey, MeasureSummary> projectSummaries = computeProjectSummaries(visibleProjects, reportDate.getYear());
        Map<String, MeasureSummary> totalSummaries = computeTotalSummaries(projectSummaries);
        return new CalendarContext(reportDate.getYear(), visibleProjects, projectSummaries, totalSummaries);
    }

    private List<ProjectContext> loadVisibleProjects(Map<String, Object> params) {
        LocalDate reportDate = getRequiredReportDate(params);
        Customer selectedCustomer = getEntityParameter(params, "customer", Customer.class);
        Contract selectedContract = getEntityParameter(params, "contract", Contract.class);
        Project selectedProject = getEntityParameter(params, "project", Project.class);

        StringBuilder query = new StringBuilder("""
                select p from Project p
                join fetch p.contract
                join fetch p.contract.customer
                where p.contract.startDate < :reportDate
                """);
        if (selectedCustomer != null) {
            query.append("\n  and p.contract.customer.id = :customerId");
        }
        if (selectedContract != null) {
            query.append("\n  and p.contract.id = :contractId");
        }
        if (selectedProject != null) {
            query.append("\n  and p.id = :projectId");
        }
        query.append("\norder by p.contract.customer.name, p.contract.startDate desc, p.contract.internalID, p.name");

        var loader = dataManager.load(Project.class)
                .query(query.toString())
                .parameter("reportDate", reportDate);
        if (selectedCustomer != null) {
            loader.parameter("customerId", selectedCustomer.getId());
        }
        if (selectedContract != null) {
            loader.parameter("contractId", selectedContract.getId());
        }
        if (selectedProject != null) {
            loader.parameter("projectId", selectedProject.getId());
        }

        return loader.list()
                .stream()
                .map(project -> new ProjectContext(
                        project.getId(),
                        project.getContract().getCustomer().getId(),
                        project.getContract().getCustomer().getName(),
                        project.getContract().getId(),
                        project.getContract().getInternalID(),
                        project.getContract().getStartDate(),
                        project.getContract().getTotalAmount(),
                        project.getName()
                ))
                .toList();
    }

    private List<Map<String, Object>> buildMasterRows(CalendarContext context) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProjectContext project : context.visibleProjects()) {
            rows.add(createProjectMasterRow(
                    project,
                    SHIPMENT_MEASURE,
                    messages.getMessage("ru.stnovator.finassist.report.cashflowCalendar/shipmentPlanned"),
                    context.projectSummaries().getOrDefault(new MeasureProjectKey(project.projectId(), SHIPMENT_MEASURE), MeasureSummary.empty())
            ));
            rows.add(createProjectMasterRow(
                    project,
                    PAYMENT_MEASURE,
                    messages.getMessage("ru.stnovator.finassist.report.cashflowCalendar/paymentPlanned"),
                    context.projectSummaries().getOrDefault(new MeasureProjectKey(project.projectId(), PAYMENT_MEASURE), MeasureSummary.empty())
            ));
        }
        return rows;
    }

    private Map<String, Object> createProjectMasterRow(
            ProjectContext project,
            String measureCode,
            String measureName,
            MeasureSummary summary
    ) {
        Map<String, Object> row = new HashMap<>();
        row.put("rowId", project.projectId() + ":" + measureCode);
        row.put("rowKind", PROJECT_ROW_KIND);
        row.put("customerId", project.customerId());
        row.put("customerName", project.customerName());
        row.put("contractId", project.contractId());
        row.put("contractInternalID", project.contractInternalId());
        row.put("contractStartDate", project.contractStartDate());
        row.put("contractPlannedSum", defaultAmount(project.contractPlannedSum()));
        row.put("beforeYear", summary.beforeYear());
        row.put("projectId", project.projectId());
        row.put("projectName", project.projectName());
        row.put("measureCode", measureCode);
        row.put("measureName", measureName);
        row.put("afterYear", summary.afterYear());
        row.put("rowTotal", summary.rowTotal());
        return row;
    }

    private Map<MeasureProjectKey, MeasureSummary> computeProjectSummaries(List<ProjectContext> visibleProjects, int reportYear) {
        Map<MeasureProjectKey, MeasureSummary> result = new LinkedHashMap<>();
        for (ProjectContext project : visibleProjects) {
            result.put(new MeasureProjectKey(project.projectId(), SHIPMENT_MEASURE), MeasureSummary.empty());
            result.put(new MeasureProjectKey(project.projectId(), PAYMENT_MEASURE), MeasureSummary.empty());
        }

        if (visibleProjects.isEmpty()) {
            return result;
        }

        List<UUID> projectIds = visibleProjects.stream()
                .map(ProjectContext::projectId)
                .toList();

        List<ShipmentScheduleItem> shipmentItems = dataManager.load(ShipmentScheduleItem.class)
                .query("""
                        select s from ShipmentScheduleItem s
                        where s.schedule.project.id in :projectIds
                        order by s.schedule.project.id, s.itemDate
                        """)
                .parameter("projectIds", projectIds)
                .list();
        for (ShipmentScheduleItem item : shipmentItems) {
            MeasureSummary summary = result.computeIfAbsent(
                    new MeasureProjectKey(item.getSchedule().getProject().getId(), SHIPMENT_MEASURE),
                    ignored -> MeasureSummary.empty()
            );
            summary.add(item.getItemDate(), defaultAmount(item.getAmount()), reportYear);
        }

        List<PaymentScheduleItem> paymentItems = dataManager.load(PaymentScheduleItem.class)
                .query("""
                        select p from PaymentScheduleItem p
                        where p.schedule.project.id in :projectIds
                        order by p.schedule.project.id, p.itemDate
                        """)
                .parameter("projectIds", projectIds)
                .list();
        for (PaymentScheduleItem item : paymentItems) {
            MeasureSummary summary = result.computeIfAbsent(
                    new MeasureProjectKey(item.getSchedule().getProject().getId(), PAYMENT_MEASURE),
                    ignored -> MeasureSummary.empty()
            );
            summary.add(item.getItemDate(), defaultAmount(item.getAmount()), reportYear);
        }

        return result;
    }

    private Map<String, MeasureSummary> computeTotalSummaries(Map<MeasureProjectKey, MeasureSummary> projectSummaries) {
        Map<String, MeasureSummary> totals = new LinkedHashMap<>();
        totals.put(SHIPMENT_MEASURE, MeasureSummary.empty());
        totals.put(PAYMENT_MEASURE, MeasureSummary.empty());

        for (Map.Entry<MeasureProjectKey, MeasureSummary> entry : projectSummaries.entrySet()) {
            totals.computeIfAbsent(entry.getKey().measureCode(), ignored -> MeasureSummary.empty())
                    .merge(entry.getValue());
        }
        return totals;
    }

    private List<Map<String, Object>> buildTotalsRows(CalendarContext context) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(createTotalsRow(
                messages.getMessage("ru.stnovator.finassist.report.cashflowCalendar/shipmentTotal"),
                context.totalSummaries().getOrDefault(SHIPMENT_MEASURE, MeasureSummary.empty())
        ));
        rows.add(createTotalsRow(
                messages.getMessage("ru.stnovator.finassist.report.cashflowCalendar/paymentTotal"),
                context.totalSummaries().getOrDefault(PAYMENT_MEASURE, MeasureSummary.empty())
        ));
        return rows;
    }

    private Map<String, Object> createTotalsRow(String measureName, MeasureSummary summary) {
        Map<String, Object> row = new HashMap<>();
        row.put("customerName", "");
        row.put("contractInternalID", "");
        row.put("projectName", messages.getMessage("ru.stnovator.finassist.report.cashflowCalendar/totalProjectLabel"));
        row.put("measureName", measureName);
        row.put("contractPlannedSum", null);
        row.put("beforeYear", summary.beforeYear());
        for (int month = 1; month <= 12; month++) {
            row.put("month%02d".formatted(month), summary.monthAmount(month));
        }
        row.put("afterYear", summary.afterYear());
        row.put("rowTotal", summary.rowTotal());
        return row;
    }

    private Map<String, Object> createHeaderRow(String monthId, String monthName, Integer month) {
        Map<String, Object> row = new HashMap<>();
        row.put("monthId", monthId);
        row.put("monthName", monthName);
        row.put("month", month);
        return row;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getDatasetRows(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof List<?> rows) {
            return (List<Map<String, Object>>) rows;
        }
        return List.of();
    }

    private <T> T getEntityParameter(Map<String, Object> params, String alias, Class<T> entityClass) {
        Object value = params.get(alias);
        if (entityClass.isInstance(value)) {
            return entityClass.cast(value);
        }
        return null;
    }

    private LocalDate getRequiredReportDate(Map<String, Object> params) {
        LocalDate reportDate = getReportDate(params);
        if (reportDate == null) {
            throw new IllegalStateException("Required reportDate parameter is missing");
        }
        return reportDate;
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

    private String formatMonthId(YearMonth yearMonth) {
        return "%04d-%02d".formatted(yearMonth.getYear(), yearMonth.getMonthValue());
    }

    private int extractMonth(String columnId) {
        return Integer.parseInt(columnId.substring(columnId.length() - 2));
    }

    private BigDecimal defaultAmount(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private record ProjectContext(
            UUID projectId,
            UUID customerId,
            String customerName,
            UUID contractId,
            String contractInternalId,
            LocalDate contractStartDate,
            BigDecimal contractPlannedSum,
            String projectName
    ) {
    }

    private record MeasureProjectKey(UUID projectId, String measureCode) {
    }

    private record CalendarContext(
            int reportYear,
            List<ProjectContext> visibleProjects,
            Map<MeasureProjectKey, MeasureSummary> projectSummaries,
            Map<String, MeasureSummary> totalSummaries
    ) {
    }

    private static final class MeasureSummary {
        private BigDecimal beforeYear = BigDecimal.ZERO;
        private BigDecimal afterYear = BigDecimal.ZERO;
        private final Map<Integer, BigDecimal> monthAmounts = new LinkedHashMap<>();
        private BigDecimal rowTotal = BigDecimal.ZERO;

        static MeasureSummary empty() {
            MeasureSummary summary = new MeasureSummary();
            for (int month = 1; month <= 12; month++) {
                summary.monthAmounts.put(month, BigDecimal.ZERO);
            }
            return summary;
        }

        void add(LocalDate itemDate, BigDecimal amount, int reportYear) {
            rowTotal = rowTotal.add(amount);
            if (itemDate.getYear() < reportYear) {
                beforeYear = beforeYear.add(amount);
                return;
            }
            if (itemDate.getYear() > reportYear) {
                afterYear = afterYear.add(amount);
                return;
            }
            monthAmounts.merge(itemDate.getMonthValue(), amount, BigDecimal::add);
        }

        void merge(MeasureSummary other) {
            beforeYear = beforeYear.add(other.beforeYear);
            afterYear = afterYear.add(other.afterYear);
            rowTotal = rowTotal.add(other.rowTotal);
            for (int month = 1; month <= 12; month++) {
                monthAmounts.merge(month, other.monthAmount(month), BigDecimal::add);
            }
        }

        BigDecimal beforeYear() {
            return beforeYear;
        }

        BigDecimal afterYear() {
            return afterYear;
        }

        BigDecimal rowTotal() {
            return rowTotal;
        }

        BigDecimal monthAmount(int month) {
            return monthAmounts.getOrDefault(month, BigDecimal.ZERO);
        }
    }
}
