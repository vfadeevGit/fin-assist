package ru.stnovator.finassist.report;

import com.vaadin.flow.component.UI;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.testassist.*;
import io.jmix.reports.entity.Report;
import io.jmix.reports.runner.ReportRunner;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.stnovator.finassist.FinAssistApplication;
import ru.stnovator.finassist.entity.User;
import ru.stnovator.finassist.test_support.DashboardFixtures;
import ru.stnovator.finassist.test_support.RelatedFixtures;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.Locale;
import static org.assertj.core.api.Assertions.assertThat;

@UiTest
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class ReportRunAccessUiTest {
    @Autowired DataManager data;
    @Autowired SystemAuthenticator auth;
    @Autowired ViewNavigators navigators;
    @Autowired ReportRunner runner;
    @Autowired io.jmix.reports.util.MsgBundleTools msgBundleTools;
    @Autowired io.jmix.core.AccessManager accessManager;

    @ParameterizedTest
    @ValueSource(strings = {"system-full-access", "finance-manager-role"})
    void businessRolesSeeReportsAndGenerateReadableXlsx(String role) throws Exception {
        var original = SecurityContextHolder.getContext().getAuthentication();
        try (var fixtures = new RelatedFixtures(data, auth)) {
            fixtures.shipment(LocalDate.of(2026, 3, 1), "1250");
            fixtures.payment(LocalDate.of(2026, 3, 10), "1250");
            User user = data.create(User.class); user.setUsername("report-check-" + role);
            user.setAuthorities(java.util.List.of(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role),
                    new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ui-minimal")));
            DashboardFixtures.authenticate(user, "ROLE_" + role, "ROLE_ui-minimal");
            UI.getCurrent().setLocale(Locale.forLanguageTag("ru"));
            com.vaadin.flow.server.VaadinSession.getCurrent().setLocale(Locale.forLanguageTag("ru"));
            navigators.view(UiTestUtils.getCurrentView(), "report_ReportRunView").navigate();
            DataGrid<Report> grid = UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "reportDataGrid");
            assertThat(grid.getItems().getItems()).extracting(Report::getCode)
                    .contains("list-of-contracts", "cashflow-calendar");
            assertThat(grid.getItems().getItems()).extracting(report -> report.getInstanceName(msgBundleTools))
                    .contains("Список контрактов", "Календарь денежных потоков");
            var parametersAccess = new io.jmix.flowui.accesscontext.UiShowViewContext("report_InputParametersDialogView");
            accessManager.applyRegisteredConstraints(parametersAccess);
            assertThat(parametersAccess.isPermitted()).isTrue();
            for (String code : new String[]{"list-of-contracts", "cashflow-calendar"}) {
                var params = new java.util.HashMap<String, Object>();
                params.put("contract", fixtures.contract);
                params.put("reportDate", LocalDate.of(2026, 11, 25));
                params.put(code.equals("list-of-contracts") ? "contractListMode" : "calendarMode",
                        code.equals("list-of-contracts") ? ContractListMode.BASE : CashflowCalendarMode.BASE);
                var output = runner.byReportCode(code).withParams(params).run();
                assertThat(output.getDocumentName()).endsWith(".xlsx");
                try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(output.getContent()))) {
                    assertThat(book.getNumberOfSheets()).isPositive();
                    var text = new StringBuilder();
                    var formatter = new org.apache.poi.ss.usermodel.DataFormatter();
                    for (var sheet : book) for (var row : sheet) for (var cell : row)
                        text.append(formatter.formatCellValue(cell)).append('\n');
                    assertThat(text.toString()).contains(fixtures.project.getName());
                }
            }
        } finally {
            SecurityContextHolder.getContext().setAuthentication(original);
        }
    }
    @org.junit.jupiter.api.Test
    void minimalUiRoleDoesNotGainReportAccess() {
        var original = SecurityContextHolder.getContext().getAuthentication();
        try {
            User user = data.create(User.class); user.setUsername("report-no-access");
            DashboardFixtures.authenticate(user, "ROLE_ui-minimal");
            for (String viewId : new String[]{"report_ReportRunView", "report_InputParametersDialogView"}) {
                var context = new io.jmix.flowui.accesscontext.UiShowViewContext(viewId);
                accessManager.applyRegisteredConstraints(context);
                assertThat(context.isPermitted()).as(viewId).isFalse();
            }
        } finally {
            SecurityContextHolder.getContext().setAuthentication(original);
        }
    }

}
