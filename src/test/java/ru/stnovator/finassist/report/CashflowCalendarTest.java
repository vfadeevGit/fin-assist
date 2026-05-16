package ru.stnovator.finassist.report;

import io.jmix.core.DataManager;
import io.jmix.reports.annotation.InputParameterDef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.entity.Addendum;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.entity.ContractType;
import ru.stnovator.finassist.entity.Customer;
import ru.stnovator.finassist.entity.LineOfBusiness;
import ru.stnovator.finassist.entity.PaymentSchedule;
import ru.stnovator.finassist.entity.PaymentScheduleCorrection;
import ru.stnovator.finassist.entity.PaymentScheduleCorrectionItem;
import ru.stnovator.finassist.entity.PaymentScheduleItem;
import ru.stnovator.finassist.entity.Project;
import ru.stnovator.finassist.entity.ShipmentSchedule;
import ru.stnovator.finassist.entity.ShipmentScheduleCorrection;
import ru.stnovator.finassist.entity.ShipmentScheduleCorrectionItem;
import ru.stnovator.finassist.entity.ShipmentScheduleItem;
import ru.stnovator.finassist.test_support.AuthenticatedAsAdmin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class CashflowCalendarTest {
    private static final String REPORT_DATE_ALIAS = "reportDate";

    @Autowired
    DataManager dataManager;

    @Autowired
    CashflowCalendar cashflowCalendar;

    private final List<Object> cleanupEntities = new ArrayList<>();

    @AfterEach
    void tearDown() {
        Collections.reverse(cleanupEntities);
        for (Object entity : cleanupEntities) {
            try {
                dataManager.remove(entity);
            } catch (Exception ignored) {
                // Parent entities may already remove children by cascade.
            }
        }
        cleanupEntities.clear();
    }

    @Test
    void parametersMatchListOfContractsAndReportDateDefaultsToCurrentDate() {
        List<String> cashflowAliases = java.util.Arrays.stream(CashflowCalendar.class.getAnnotationsByType(InputParameterDef.class))
                .map(InputParameterDef::alias)
                .toList();
        List<String> listOfContractsAliases = java.util.Arrays.stream(ListOfContracts.class.getAnnotationsByType(InputParameterDef.class))
                .map(InputParameterDef::alias)
                .toList();

        assertThat(cashflowAliases).containsExactlyElementsOf(listOfContractsAliases);

        InputParameterDef reportDateParameter = findInputParameter(CashflowCalendar.class, REPORT_DATE_ALIAS);
        assertThat(reportDateParameter.type().name()).isEqualTo("DATE");
        assertThat(reportDateParameter.defaultDateIsCurrent()).isTrue();
        assertThat(reportDateParameter.required()).isTrue();
    }

    @Test
    void dynamicHeaderContainsContractSumBoundaryAndRowTotalColumns() {
        List<Map<String, Object>> headers = cashflowCalendar.calendarDynamicHeaderDataLoader()
                .loadData(null, null, Map.of("reportDate", LocalDate.of(2026, 5, 16)));

        assertThat(headers).hasSize(12);
        assertThat(headers.getFirst()).containsEntry("monthId", "2026-01");
        assertThat(headers.get(11)).containsEntry("monthId", "2026-12");
    }

    @Test
    void reportAddsProjectTotalsAndMonthlyTotalRowsUsingBaseScheduleItemsOnly() {
        ContractFixture eligibleFixture = createFixture("eligible", LocalDate.of(2026, 1, 1), 1200);
        ContractFixture futureFixture = createFixture("future", LocalDate.of(2026, 7, 1), 900);

        createShipmentItem(eligibleFixture.shipmentSchedule, LocalDate.of(2025, 12, 20), 25);
        createShipmentItem(eligibleFixture.shipmentSchedule, LocalDate.of(2026, 1, 5), 100);
        createShipmentItem(eligibleFixture.shipmentSchedule, LocalDate.of(2026, 1, 20), 50);
        createShipmentItem(eligibleFixture.shipmentSchedule, LocalDate.of(2027, 1, 10), 30);

        createPaymentItem(eligibleFixture.paymentSchedule, LocalDate.of(2025, 11, 15), 15);
        createPaymentItem(eligibleFixture.paymentSchedule, LocalDate.of(2026, 1, 10), 40);
        createPaymentItem(eligibleFixture.paymentSchedule, LocalDate.of(2026, 2, 8), 20);
        createPaymentItem(eligibleFixture.paymentSchedule, LocalDate.of(2027, 3, 1), 35);

        createShipmentItem(futureFixture.shipmentSchedule, LocalDate.of(2026, 1, 15), 700);
        createPaymentItem(futureFixture.paymentSchedule, LocalDate.of(2026, 1, 15), 300);

        Addendum addendum = createAddendum(eligibleFixture.contract, "A-01", LocalDate.of(2026, 2, 1));
        createShipmentCorrectionItem(eligibleFixture.project, addendum, LocalDate.of(2026, 1, 5), 900);
        createPaymentCorrectionItem(eligibleFixture.project, addendum, LocalDate.of(2026, 2, 8), 800);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reportDate", LocalDate.of(2026, 5, 16));
        params.put("includeAddendum", true);
        params.put("customer", eligibleFixture.customer);

        List<Map<String, Object>> masterRows = cashflowCalendar.calendarMasterDataLoader()
                .loadData(null, null, params);
        List<Map<String, Object>> headers = cashflowCalendar.calendarDynamicHeaderDataLoader()
                .loadData(null, null, params);

        params.put("Calendar_master_data", masterRows);
        params.put("Calendar_dynamic_header", headers);

        List<Map<String, Object>> cells = cashflowCalendar.calendarDataLoader()
                .loadData(null, null, params);

        List<Map<String, Object>> totalsRows = cashflowCalendar.calendarTotalsDataLoader()
                .loadData(null, null, params);

        assertThat(masterRows).hasSize(2);
        assertThat(masterRows).extracting(row -> row.get("measureName"))
                .containsExactly("План отгрузки", "План оплаты");

        Map<String, Map<String, BigDecimal>> matrix = toMatrix(cells);
        String shipmentRowId = eligibleFixture.project.getId() + ":SHIPMENT";
        String paymentRowId = eligibleFixture.project.getId() + ":PAYMENT";

        Map<String, Object> shipmentRow = masterRows.getFirst();
        assertThat((BigDecimal) shipmentRow.get("contractPlannedSum")).isEqualByComparingTo("1200");
        assertThat((BigDecimal) shipmentRow.get("beforeYear")).isEqualByComparingTo("25");
        assertThat((BigDecimal) shipmentRow.get("afterYear")).isEqualByComparingTo("30");
        assertThat((BigDecimal) shipmentRow.get("rowTotal")).isEqualByComparingTo("205");
        assertThat(matrix.get(shipmentRowId).get("2026-01")).isEqualByComparingTo("150");

        Map<String, Object> paymentRow = masterRows.get(1);
        assertThat((BigDecimal) paymentRow.get("contractPlannedSum")).isEqualByComparingTo("1200");
        assertThat((BigDecimal) paymentRow.get("beforeYear")).isEqualByComparingTo("15");
        assertThat((BigDecimal) paymentRow.get("afterYear")).isEqualByComparingTo("35");
        assertThat((BigDecimal) paymentRow.get("rowTotal")).isEqualByComparingTo("110");
        assertThat(matrix.get(paymentRowId).get("2026-01")).isEqualByComparingTo("40");
        assertThat(matrix.get(paymentRowId).get("2026-02")).isEqualByComparingTo("20");

        assertThat(totalsRows).hasSize(2);
        Map<String, Object> shipmentTotals = totalsRows.getFirst();
        assertThat(shipmentTotals.get("projectName")).isEqualTo("Все проекты");
        assertThat(shipmentTotals.get("measureName")).isEqualTo("Итого отгрузка");
        assertThat(shipmentTotals.get("contractPlannedSum")).isNull();
        assertThat((BigDecimal) shipmentTotals.get("beforeYear")).isEqualByComparingTo("25");
        assertThat((BigDecimal) shipmentTotals.get("month01")).isEqualByComparingTo("150");
        assertThat((BigDecimal) shipmentTotals.get("afterYear")).isEqualByComparingTo("30");
        assertThat((BigDecimal) shipmentTotals.get("rowTotal")).isEqualByComparingTo("205");

        Map<String, Object> paymentTotals = totalsRows.get(1);
        assertThat(paymentTotals.get("measureName")).isEqualTo("Итого оплата");
        assertThat((BigDecimal) paymentTotals.get("beforeYear")).isEqualByComparingTo("15");
        assertThat((BigDecimal) paymentTotals.get("month01")).isEqualByComparingTo("40");
        assertThat((BigDecimal) paymentTotals.get("month02")).isEqualByComparingTo("20");
        assertThat((BigDecimal) paymentTotals.get("afterYear")).isEqualByComparingTo("35");
        assertThat((BigDecimal) paymentTotals.get("rowTotal")).isEqualByComparingTo("110");

        assertThat(matrix.get(shipmentRowId)).doesNotContainValue(BigDecimal.valueOf(900));
        assertThat(matrix.get(paymentRowId)).doesNotContainValue(BigDecimal.valueOf(800));
    }

    @Test
    void customerContractAndProjectFiltersAreAppliedCumulatively() {
        ContractFixture firstFixture = createFixture("first", LocalDate.of(2026, 1, 1), 1000);
        ContractFixture secondFixture = createFixture("second", LocalDate.of(2026, 1, 1), 2000);

        createShipmentItem(firstFixture.shipmentSchedule, LocalDate.of(2026, 3, 3), 10);
        createPaymentItem(firstFixture.paymentSchedule, LocalDate.of(2026, 3, 3), 5);
        createShipmentItem(secondFixture.shipmentSchedule, LocalDate.of(2026, 3, 3), 20);
        createPaymentItem(secondFixture.paymentSchedule, LocalDate.of(2026, 3, 3), 15);

        Map<String, Object> matchingParams = new LinkedHashMap<>();
        matchingParams.put("reportDate", LocalDate.of(2026, 5, 16));
        matchingParams.put("customer", firstFixture.customer);
        matchingParams.put("contract", firstFixture.contract);
        matchingParams.put("project", firstFixture.project);

        List<Map<String, Object>> filteredRows = cashflowCalendar.calendarMasterDataLoader()
                .loadData(null, null, matchingParams);
        List<Map<String, Object>> filteredTotals = cashflowCalendar.calendarTotalsDataLoader()
                .loadData(null, null, matchingParams);

        assertThat(filteredRows).hasSize(2);
        assertThat(filteredRows).extracting(row -> row.get("projectId"))
                .contains(firstFixture.project.getId())
                .doesNotContain(secondFixture.project.getId());
        assertThat(filteredTotals).hasSize(2);

        Map<String, Object> inconsistentParams = new LinkedHashMap<>();
        inconsistentParams.put("reportDate", LocalDate.of(2026, 5, 16));
        inconsistentParams.put("customer", firstFixture.customer);
        inconsistentParams.put("contract", secondFixture.contract);

        List<Map<String, Object>> inconsistentRows = cashflowCalendar.calendarMasterDataLoader()
                .loadData(null, null, inconsistentParams);
        List<Map<String, Object>> inconsistentTotals = cashflowCalendar.calendarTotalsDataLoader()
                .loadData(null, null, inconsistentParams);

        assertThat(inconsistentRows).isEmpty();
        assertThat(inconsistentTotals).hasSize(2);
        assertThat(inconsistentTotals).allSatisfy(row ->
                assertThat((BigDecimal) row.get("rowTotal")).isEqualByComparingTo("0"));
    }

    private Map<String, Map<String, BigDecimal>> toMatrix(List<Map<String, Object>> cells) {
        Map<String, Map<String, BigDecimal>> matrix = new LinkedHashMap<>();
        for (Map<String, Object> cell : cells) {
            matrix.computeIfAbsent((String) cell.get("Calendar_master_data@rowId"), ignored -> new LinkedHashMap<>())
                    .put((String) cell.get("Calendar_dynamic_header@monthId"), (BigDecimal) cell.get("amount"));
        }
        return matrix;
    }

    private InputParameterDef findInputParameter(Class<?> reportClass, String alias) {
        for (InputParameterDef annotation : reportClass.getAnnotationsByType(InputParameterDef.class)) {
            if (alias.equals(annotation.alias())) {
                return annotation;
            }
        }
        throw new IllegalStateException("Input parameter not found: " + alias);
    }

    private ContractFixture createFixture(String suffix, LocalDate contractStartDate, int totalAmount) {
        Customer customer = dataManager.create(Customer.class);
        customer.setName("Customer-" + suffix + "-" + UUID.randomUUID());
        customer = dataManager.save(customer);
        cleanupEntities.add(customer);

        LineOfBusiness lineOfBusiness = dataManager.create(LineOfBusiness.class);
        lineOfBusiness.setName("LOB-" + suffix + "-" + UUID.randomUUID());
        lineOfBusiness = dataManager.save(lineOfBusiness);
        cleanupEntities.add(lineOfBusiness);

        Contract contract = dataManager.create(Contract.class);
        contract.setCustomer(customer);
        contract.setInternalID("CTR-" + suffix + "-" + UUID.randomUUID());
        contract.setStartDate(contractStartDate);
        contract.setEndDate(contractStartDate.plusMonths(11));
        contract.setPaymentType(ContractType.DIRECT_CONSUMER);
        contract.setTotalAmount(BigDecimal.valueOf(totalAmount));
        contract = dataManager.save(contract);
        cleanupEntities.add(contract);

        Project project = dataManager.create(Project.class);
        project.setContract(contract);
        project.setLineOfBusiness(lineOfBusiness);
        project.setName("Project-" + suffix + "-" + UUID.randomUUID());
        project = dataManager.save(project);

        ShipmentSchedule shipmentSchedule = dataManager.create(ShipmentSchedule.class);
        shipmentSchedule.setProject(project);
        shipmentSchedule = dataManager.save(shipmentSchedule);

        PaymentSchedule paymentSchedule = dataManager.create(PaymentSchedule.class);
        paymentSchedule.setProject(project);
        paymentSchedule = dataManager.save(paymentSchedule);

        return new ContractFixture(customer, contract, project, shipmentSchedule, paymentSchedule);
    }

    private Addendum createAddendum(Contract contract, String number, LocalDate effectiveDate) {
        Addendum addendum = dataManager.create(Addendum.class);
        addendum.setContract(contract);
        addendum.setNumber(number);
        addendum.setEffectiveDate(effectiveDate);
        return dataManager.save(addendum);
    }

    private void createShipmentItem(ShipmentSchedule schedule, LocalDate itemDate, int amount) {
        ShipmentScheduleItem item = dataManager.create(ShipmentScheduleItem.class);
        item.setSchedule(schedule);
        item.setItemDate(itemDate);
        item.setAmount(BigDecimal.valueOf(amount));
        dataManager.save(item);
    }

    private void createPaymentItem(PaymentSchedule schedule, LocalDate itemDate, int amount) {
        PaymentScheduleItem item = dataManager.create(PaymentScheduleItem.class);
        item.setSchedule(schedule);
        item.setItemDate(itemDate);
        item.setAmount(BigDecimal.valueOf(amount));
        dataManager.save(item);
    }

    private void createShipmentCorrectionItem(Project project, Addendum addendum, LocalDate itemDate, int amount) {
        ShipmentScheduleCorrection correction = dataManager.load(ShipmentScheduleCorrection.class)
                .query("select c from ShipmentScheduleCorrection c where c.project = :project and c.addendum = :addendum")
                .parameter("project", project)
                .parameter("addendum", addendum)
                .optional()
                .orElseGet(() -> {
                    ShipmentScheduleCorrection created = dataManager.create(ShipmentScheduleCorrection.class);
                    created.setProject(project);
                    created.setAddendum(addendum);
                    return dataManager.save(created);
                });

        ShipmentScheduleCorrectionItem item = dataManager.create(ShipmentScheduleCorrectionItem.class);
        item.setCorrection(correction);
        item.setItemDate(itemDate);
        item.setAmount(BigDecimal.valueOf(amount));
        dataManager.save(item);
    }

    private void createPaymentCorrectionItem(Project project, Addendum addendum, LocalDate itemDate, int amount) {
        PaymentScheduleCorrection correction = dataManager.load(PaymentScheduleCorrection.class)
                .query("select c from PaymentScheduleCorrection c where c.project = :project and c.addendum = :addendum")
                .parameter("project", project)
                .parameter("addendum", addendum)
                .optional()
                .orElseGet(() -> {
                    PaymentScheduleCorrection created = dataManager.create(PaymentScheduleCorrection.class);
                    created.setProject(project);
                    created.setAddendum(addendum);
                    return dataManager.save(created);
                });

        PaymentScheduleCorrectionItem item = dataManager.create(PaymentScheduleCorrectionItem.class);
        item.setCorrection(correction);
        item.setItemDate(itemDate);
        item.setAmount(BigDecimal.valueOf(amount));
        dataManager.save(item);
    }

    private record ContractFixture(
            Customer customer,
            Contract contract,
            Project project,
            ShipmentSchedule shipmentSchedule,
            PaymentSchedule paymentSchedule
    ) {
    }
}
