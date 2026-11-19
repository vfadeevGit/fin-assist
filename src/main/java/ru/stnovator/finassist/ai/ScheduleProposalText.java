package ru.stnovator.finassist.ai;

import ru.stnovator.finassist.ai.schedule.ScheduleImportPreview;
import ru.stnovator.finassist.ai.schedule.ScheduleImportTypes;

import java.util.Objects;
import io.jmix.core.Messages;
import org.springframework.stereotype.Component;

/** Authoritative native assistant content, including every normalized row and date-bucket difference. */
@Component
class ScheduleProposalText {
    private final Messages messages;

    ScheduleProposalText(Messages messages) {
        this.messages = messages;
    }

    String label(String key) {
        return messages.getMessage("ru.stnovator.finassist.ai.view/scheduleImport." + key);
    }

    String action(ScheduleImportTypes.Mode mode) {
        return label("proposed" + mode);
    }

    String render(ScheduleImportPreview preview, String source) {
        var text = new StringBuilder(action(preview.mode())).append(". ").append(label("kind" + preview.kind()));
        field(text, "contract", literal(preview.contractCaption()));
        field(text, "project", literal(preview.projectCaption()));
        field(text, "source", literal(source));
        if (preview.number() != null)
            field(text, "addendum", literal(preview.number()) + " · " + preview.effectiveDate());
        field(text, "rowCount", String.valueOf(preview.rows().size()));
        field(text, "total", preview.total().toPlainString());
        text.append("\n\n| ").append(label("date")).append(" | ").append(label("amount")).append(" |\n| --- | ---: |\n");
        for (var row : preview.rows())
            text.append("| ").append(row.date()).append(" | ").append(row.amount().toPlainString()).append(" |\n");
        text.append("\n| ").append(label("date")).append(" | ").append(label("before")).append(" | ")
                .append(label("after")).append(" | ").append(label("difference")).append(" |\n| --- | ---: | ---: | ---: |\n");
        for (var row : preview.comparison().rows())
            text.append("| ").append(row.date()).append(" | ").append(Objects.toString(row.before(), "—"))
                    .append(" | ").append(Objects.toString(row.after(), "—")).append(" | ").append(row.delta().toPlainString()).append(" |\n");
        return text.append("\n").append(label("applyQuestion")).toString();
    }

    private void field(StringBuilder text, String key, String value) {
        text.append("\n\n").append(label(key)).append(": ").append(value);
    }

    private static String literal(String input) {
        // Only fixed server text introduces Markdown structure.
        var text = new StringBuilder();
        input.codePoints().forEach(c -> {
            if (Character.isISOControl(c) || Character.isWhitespace(c)) text.append(' ');
            else if (c < 128 && !Character.isLetterOrDigit(c)) text.append("&#").append(c).append(';');
            else text.appendCodePoint(c);
        });
        return text.toString();
    }
}
