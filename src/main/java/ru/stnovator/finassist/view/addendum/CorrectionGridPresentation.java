package ru.stnovator.finassist.view.addendum;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import io.jmix.core.entity.EntitySystemAccess;
import io.jmix.core.entity.EntityValues;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.view.MessageBundle;
import ru.stnovator.finassist.entity.Addendum;
import ru.stnovator.finassist.service.CorrectionComparisonService;
import ru.stnovator.finassist.service.RelatedDataAccess;
import ru.stnovator.finassist.service.ScheduleComparison;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** View-owned presentation: no database work in cell renderers. */
final class CorrectionGridPresentation<T> {
    private final DataGrid<T> grid;
    private final CollectionContainer<T> container;
    private final Supplier<Addendum> draft;
    private final CorrectionComparisonService service;
    private final CorrectionComparisonService.Kind kind;
    private final RelatedDataAccess access;
    private final MessageBundle messages;
    private final Map<Object, ScheduleComparison> comparisons = new HashMap<>();
    private final Map<Object, Boolean> showAll = new HashMap<>();
    private final Grid.Column<T> amountColumn;
    private final Grid.Column<T> deltaColumn;
    private final NumberFormat money = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"));
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final DateTimeFormatter AUDIT = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm XXX");
    private Key key;
    private CorrectionComparisonService.Baselines baselines;
    private record Key(UUID contract, LocalDate date, UUID current, Set<UUID> projects) {}

    CorrectionGridPresentation(DataGrid<T> grid, CollectionContainer<T> container, Supplier<Addendum> draft,
                               CorrectionComparisonService service, CorrectionComparisonService.Kind kind,
                               RelatedDataAccess access, MessageBundle messages) {
        this.grid = grid; this.container = container; this.draft = draft; this.service = service;
        this.kind = kind; this.access = access; this.messages = messages;
        money.setMinimumFractionDigits(2); money.setMaximumFractionDigits(2);
        grid.addClassName("correction-summary-grid");
        grid.setDetailsVisibleOnClick(false);
        var expand = grid.addColumn(new ComponentRenderer<>(this::expander)).setKey("comparisonDetails")
                .setHeader("").setWidth("3.5rem").setFlexGrow(0);
        var project = grid.getColumnByKey("project");
        project.setWidth("15rem").setFlexGrow(1);
        amountColumn = grid.addColumn(c -> amountSummary(comparison(c))).setKey("comparisonAmount")
                .setHeader(msg("comparisonAmount")).setWidth("19rem").setFlexGrow(0);
        deltaColumn = grid.addColumn(c -> signed(comparison(c).delta())).setKey("comparisonDelta")
                .setHeader(msg("comparisonDelta")).setWidth("13rem").setFlexGrow(0);
        var end = grid.addColumn(c -> endSummary(comparison(c))).setKey("comparisonEnd")
                .setHeader(msg("comparisonEnd")).setWidth("19rem").setFlexGrow(0);
        grid.setColumnOrder(expand, project, amountColumn, deltaColumn, end);
        grid.setItemDetailsRenderer(new ComponentRenderer<>(this::details));
    }

    void refresh() {
        Addendum a = draft.get();
        Set<UUID> projects = container.getItems().stream().map(c -> (UUID) EntityValues.getValueEx(c, "project.id"))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Key next = new Key(a.getContract() == null ? null : a.getContract().getId(), a.getEffectiveDate(), a.getId(), Set.copyOf(projects));
        if (!next.equals(key)) {
            baselines = service.load(kind, next.contract(), next.date(), next.current(), next.projects()); key = next;
        }
        comparisons.clear();
        for (T c : container.getItems()) {
            UUID project = EntityValues.getValueEx(c, "project.id");
            boolean erased = !EntitySystemAccess.getSecurityState(c).getErasedAttributes().isEmpty()
                    || CorrectionComparisonService.items(c).stream().anyMatch(i ->
                    !EntitySystemAccess.getSecurityState(i).getErasedAttributes().isEmpty());
            ScheduleComparison value = !baselines.available() || project == null || erased ? ScheduleComparison.unavailable()
                    : ScheduleComparison.compare(baselines.schedules().getOrDefault(project, List.of()),
                    CorrectionComparisonService.entries(CorrectionComparisonService.items(c)));
            comparisons.put(EntityValues.getId(c), value);
        }
        showAll.keySet().retainAll(comparisons.keySet());
        updateTotals();
        grid.getDataProvider().refreshAll();
        resize();
    }

    void resize() {
        boolean expanded = container.getItems().stream().anyMatch(grid::isDetailsVisible);
        int height = expanded ? 448 : 96 + Math.min(8, Math.max(2, container.getItems().size())) * 44;
        grid.setHeight(height + "px");
    }
    private ScheduleComparison comparison(T correction) {
        return comparisons.getOrDefault(EntityValues.getId(correction), ScheduleComparison.unavailable());
    }
    private String amountSummary(ScheduleComparison c) {
        return switch (c.status()) {
            case COMPLETE -> amount(c.beforeTotal()) + " → " + amount(c.afterTotal());
            case EMPTY -> msg("comparisonEmpty");
            case INCOMPLETE -> msg("comparisonIncomplete");
            case UNAVAILABLE -> msg("comparisonUnavailable");
        };
    }
    private String endSummary(ScheduleComparison c) {
        return c.status() == ScheduleComparison.Status.COMPLETE ? date(c.beforeEnd()) + " → " + date(c.afterEnd()) : "—";
    }
    private Component expander(T c) {
        Button button = new Button(); button.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        button.setId("comparison-expand-" + EntityValues.getId(c));
        button.getElement().setAttribute("aria-label", msg("comparisonExpand"));
        updateToggle(button, grid.isDetailsVisible(c));
        button.addClickListener(e -> {
            boolean open = !grid.isDetailsVisible(c);
            grid.setDetailsVisible(c, open); updateToggle(button, open); resize();
        });
        return button;
    }
    private void updateToggle(Button button, boolean open) {
        button.setIcon((open ? VaadinIcon.ANGLE_DOWN : VaadinIcon.ANGLE_RIGHT).create());
        button.getElement().setAttribute("aria-expanded", Boolean.toString(open));
    }
    private Component details(T correction) {
        ScheduleComparison comparison = comparison(correction);
        VerticalLayout panel = new VerticalLayout(); panel.setPadding(false); panel.setSpacing(true);
        panel.addClassName("correction-comparison-details");
        if (comparison.status() == ScheduleComparison.Status.COMPLETE) {
            Checkbox all = new Checkbox(msg("comparisonShowAll"));
            all.setValue(showAll.getOrDefault(EntityValues.getId(correction), false));
            Grid<ScheduleComparison.Row> rows = new Grid<>();
            rows.addColumn(r -> date(r.date())).setHeader(msg("comparisonDate")).setWidth("9rem").setFlexGrow(0);
            rows.addColumn(r -> amount(r.before())).setHeader(msg("comparisonBefore")).setWidth("12rem");
            rows.addColumn(r -> amount(r.after())).setHeader(msg("comparisonAfter")).setWidth("12rem");
            rows.addColumn(r -> signed(r.delta())).setHeader(msg("comparisonDifference")).setWidth("12rem");
            rows.setSelectionMode(Grid.SelectionMode.NONE); rows.setWidthFull(); rows.setHeight("15rem");
            rows.getElement().setAttribute("aria-label", msg("comparisonDateDetails"));
            Span unchanged = new Span(msg("comparisonUnchanged"));
            Runnable update = () -> {
                var items = comparison.rows().stream().filter(r -> all.getValue() || r.changed()).toList();
                rows.setItems(items); rows.setVisible(!items.isEmpty()); unchanged.setVisible(items.isEmpty());
            };
            all.addValueChangeListener(e -> { showAll.put(EntityValues.getId(correction), e.getValue()); update.run(); });
            update.run(); panel.add(all, unchanged, rows);
        } else panel.add(new Span(amountSummary(comparison)));
        List<String> audit = new ArrayList<>();
        for (String property : List.of("createdDate", "lastModifiedDate")) {
            if (access.canRead(kind.correction, property)) {
                OffsetDateTime value = EntityValues.getValue(correction, property);
                if (value != null) audit.add(msg("AddendumDetailView." + property) + ": " + AUDIT.format(value));
            }
        }
        if (!audit.isEmpty()) {
            Span timestamps = new Span(String.join(" · ", audit)); timestamps.addClassName("correction-audit"); panel.add(timestamps);
        }
        return panel;
    }
    private void updateTotals() {
        grid.getColumnByKey("project").setFooter(msg("comparisonTotal"));
        if (comparisons.isEmpty()) { amountColumn.setFooter("—"); deltaColumn.setFooter("—"); return; }
        if (comparisons.values().stream().anyMatch(c -> c.status() != ScheduleComparison.Status.COMPLETE)) {
            amountColumn.setFooter(msg("comparisonTotalsIncomplete")); deltaColumn.setFooter("—"); return;
        }
        BigDecimal before = BigDecimal.ZERO, after = BigDecimal.ZERO;
        for (var c : comparisons.values()) { before = before.add(c.beforeTotal()); after = after.add(c.afterTotal()); }
        amountColumn.setFooter(amount(before) + " → " + amount(after)); deltaColumn.setFooter(signed(after.subtract(before)));
    }
    private String msg(String key) { return messages.getMessage(key); }
    private String date(LocalDate date) { return date == null ? "—" : DATE.format(date); }
    private String amount(BigDecimal amount) { return amount == null ? "—" : money.format(amount); }
    private String signed(BigDecimal amount) { return amount == null ? "—" : (amount.signum() > 0 ? "+" : "") + money.format(amount); }
}
