package ru.stnovator.finassist.ai.view;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import io.jmix.core.Messages;
import ru.stnovator.finassist.ai.EntityLinksTool;
import ru.stnovator.finassist.ai.schedule.*;
import ru.stnovator.finassist.service.ScheduleComparison;

import java.util.*;
import java.util.function.Consumer;

import static ru.stnovator.finassist.ai.schedule.ScheduleImportStore.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

/**
 * Displays only complete server previews, with a conversation-scoped explicit confirmation.
 */
public class ScheduleImportPanel extends VerticalLayout {
    private final ScheduleImportStore store;
    private final ScheduleImportWorkflow workflow;
    private final ScheduleImportTool tool;
    private final ScheduleImportCommitService commit;
    private final EntityLinksTool links;
    private final Messages messages;
    private final ScheduleAttachmentService attachments;
    private final VerticalLayout operations = new VerticalLayout(), receipts = new VerticalLayout(), details = new VerticalLayout();
    private final Span status = new Span();
    private final Grid<Row> rows = new Grid<>();
    private final Grid<ScheduleComparison.Row> changes = new Grid<>();
    private final Button confirm, correct, cancel;
    private UUID conversationId, operationId;
    private long revision;
    private ScheduleImportPreview preview;
    private ScheduleImportCommitService.CommitResult result;

    public ScheduleImportPanel(ScheduleImportStore store, ScheduleImportWorkflow workflow, ScheduleImportTool tool,
                               ScheduleImportCommitService commit, EntityLinksTool links, Messages messages, ScheduleAttachmentService attachments, Consumer<String> edit) {
        this.store = store;
        this.workflow = workflow;
        this.tool = tool;
        this.commit = commit;
        this.links = links;
        this.messages = messages;
        this.attachments = attachments;
        setId("scheduleImportPanel");
        operations.setPadding(false);
        receipts.setPadding(false);
        details.setPadding(false);
        operations.setSpacing(false);
        receipts.setSpacing(false);
        setWidthFull();
        setPadding(false);
        setMaxHeight("55vh");
        getStyle().set("overflow-y", "auto");
        rows.setId("schedulePreviewRows");
        rows.addColumn(Row::date).setHeader(text("date"));
        rows.addColumn(Row::amount).setHeader(text("amount"));
        rows.setWidthFull();
        rows.setHeight("18em");
        changes.setId("schedulePreviewChanges");
        changes.addColumn(ScheduleComparison.Row::date).setHeader(text("date"));
        changes.addColumn(ScheduleComparison.Row::before).setHeader(text("before"));
        changes.addColumn(ScheduleComparison.Row::after).setHeader(text("after"));
        changes.addColumn(ScheduleComparison.Row::delta).setHeader(text("difference"));
        changes.setWidthFull();
        changes.setHeight("14em");
        confirm = new Button(text("confirm"), e -> confirmDisplayed());
        confirm.setId("scheduleConfirm");
        confirm.setEnabled(false);
        correct = new Button(text("correct"), e -> {
            invalidate();
            edit.accept(text("correctPrompt"));
        });
        correct.setId("scheduleCorrect");
        cancel = new Button(text("cancel"), e -> cancelDisplayed());
        cancel.setId("scheduleCancel");
        var refresh = new Button(text("refresh"), e -> bind(conversationId));
        refresh.setId("scheduleRefresh");
        add(status, operations, receipts, details, new HorizontalLayout(confirm, correct, cancel, refresh));
        clear();
    }

    private String text(String key) {
        return messages.getMessage("ru.stnovator.finassist.ai.view/scheduleImport." + key);
    }

    public void bind(UUID conversationId) {
        clear();
        this.conversationId = conversationId;
        if (conversationId == null) return;
        try {
            var states = tool.conversationState(conversationId);
            var saved = store.savedForConversation(conversationId);
            setVisible(!states.isEmpty() || !saved.isEmpty());
            int index = 0;
            for (var state : states) {
                var button = new Button(text("operation") + " " + (++index) + (state.kind() == null ? "" : " · " + text("kind" + state.kind())), e -> select(state));
                operations.add(button);
            }
            // A single active operation is unambiguous; several remain explicitly selectable.
            if (states.size() == 1) select(states.getFirst());
            for (var receipt : saved) restoreReceipt(receipt);
        }
        catch (RuntimeException unavailable) {
            fail();
        }
    }

    private void select(ScheduleImportTool.State state) {
        invalidate();
        operationId = state.operationId();
        revision = state.revision();
        cancel.setEnabled(true);
        if ("UI_CONFIRMATION".equals(state.nextRequired())) showPreview(operationId, revision);
        else status.setText(text("SELECTION_UNAVAILABLE".equals(state.nextRequired()) ? "unavailable" : "collecting"));
    }

    public void showPreview(UUID operationId, long revision) {
        invalidate();
        try {
            var draft = owned(operationId);
            var state = tool.conversationState(conversationId).stream().filter(s -> s.operationId().equals(operationId) && s.revision() == revision
                    && "UI_CONFIRMATION".equals(s.nextRequired())).findFirst().orElseThrow();
            if (draft.status() != Status.PREVIEW_READY || draft.revision() != state.revision())
                throw new ConcurrentModificationException();
            var full = workflow.getPreview(operationId, revision);
            if (!operationId.equals(full.operationId()) || revision != full.revision() || full.noChanges())
                throw new IllegalStateException();
            this.operationId = operationId;
            this.revision = revision;
            preview = full;
            result = null;
            details.add(new Span(full.contractCaption() + " · " + full.projectCaption()),
                    new Span(text("kind" + full.kind()) + " · " + text("mode" + full.mode())),
                    new Span(text("source") + ": " + sourceCaption(draft)),
                    new Span(text("addendum") + ": " + Objects.toString(full.number(), "—") + " · " + Objects.toString(full.effectiveDate(), "—")),
                    new Span(text("total") + ": " + full.total()), rows,
                    new Span(text("changes") + ": " + full.comparison().beforeTotal() + " → " + full.comparison().afterTotal() + " (" + full.comparison().delta() + ")"), changes);
            rows.setItems(full.rows());
            changes.setItems(full.comparison().rows());
            status.setText(text("ready"));
            confirm.setEnabled(true);
            correct.setEnabled(true);
            cancel.setEnabled(true);
            setVisible(true);
        }
        catch (RuntimeException unavailable) {
            fail();
        }
    }

    private String sourceCaption(Draft draft) {
        var content = draft.content();
        if (content.source() == null) return text("messageSource");
        String name = attachments.boundFileName(content.source(), conversationId, content.sourceMessageId());
        return name + (content.mapping() == null ? "" : " · " + text("sheet") + " " + content.mapping().sheetKey());
    }

    private Draft owned(UUID id) {
        var draft = store.load(id);
        if (conversationId == null || !conversationId.equals(draft.conversationId())) throw new SecurityException();
        return draft;
    }

    private void confirmDisplayed() {
        if (preview == null || !confirm.isEnabled() || result != null) return;
        confirm.setEnabled(false);
        try {
            var draft = owned(operationId);
            if (draft.status() != Status.SAVED) {
                if (tool.conversationState(conversationId).stream().noneMatch(s -> s.operationId().equals(operationId)
                        && s.revision() == revision && "UI_CONFIRMATION".equals(s.nextRequired())))
                    throw new ConcurrentModificationException();
                workflow.getPreview(operationId, revision);
            }
            result = commit.confirm(operationId, revision);
            status.setText(text("saved"));
            correct.setEnabled(false);
            cancel.setEnabled(false);
            addLink(receipts, result.entityName(), result.entityId(), preview.kind(), preview.mode());
        }
        catch (RuntimeException unavailable) {
            fail();
        }
    }

    private void cancelDisplayed() {
        if (operationId == null) return;
        try {
            owned(operationId);
            store.cancel(operationId, revision);
            bind(conversationId);
        }
        catch (RuntimeException unavailable) {
            fail();
        }
    }

    private void restoreReceipt(Draft receipt) {
        if (!Objects.equals(conversationId, receipt.conversationId())) return;
        var current = owned(receipt.operationId());
        if (current.status() != Status.SAVED || current.resultId() == null) return;
        String entity = current.content().mode() == Mode.ADDENDUM ? "Addendum" : current.content().kind() == Kind.PAYMENT ? "PaymentSchedule" : "ShipmentSchedule";
        addLink(receipts, entity, current.resultId(), current.content().kind(), current.content().mode());
    }

    private void addLink(VerticalLayout target, String entity, UUID id, Kind kind, Mode mode) {
        var resolved = links.getEntityLinks(entity, List.of(id.toString()));
        if (resolved != null)
            resolved.stream().filter(EntityLinksTool.Link::available)
                    .forEach(link -> target.add(new Anchor(link.url(), text(mode == Mode.ADDENDUM ? "savedADDENDUM" : "saved" + kind))));
    }

    private void fail() {
        invalidate();
        status.setText(text("stale"));
        setVisible(true);
    }

    private void invalidate() {
        preview = null;
        confirm.setEnabled(false);
        correct.setEnabled(false);
        details.removeAll();
        rows.setItems(List.of());
        changes.setItems(List.of());
    }

    public void clear() {
        invalidate();
        operationId = null;
        revision = 0;
        result = null;
        conversationId = null;
        status.setText("");
        operations.removeAll();
        receipts.removeAll();
        cancel.setEnabled(false);
        setVisible(false);
    }

    public ScheduleImportPreview getPreview() {
        return preview;
    }

    public Grid<Row> getRowsGrid() {
        return rows;
    }

    public Grid<ScheduleComparison.Row> getChangesGrid() {
        return changes;
    }

    public Button getConfirmButton() {
        return confirm;
    }

    public ScheduleImportCommitService.CommitResult getResult() {
        return result;
    }
}
