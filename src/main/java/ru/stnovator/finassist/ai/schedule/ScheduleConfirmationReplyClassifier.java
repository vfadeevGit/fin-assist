package ru.stnovator.finassist.ai.schedule;

import java.util.Locale;

public final class ScheduleConfirmationReplyClassifier {
    public enum Intent { ACCEPT, DECLINE, NEEDS_DIALOGUE }

    private ScheduleConfirmationReplyClassifier() {}

    public static Intent classify(String text) {
        if (text == null) return Intent.NEEDS_DIALOGUE;
        String normalized = text.strip().toLowerCase(Locale.ROOT).replaceAll("[.!]+$", "")
                .replaceAll("(?U)\\s+", " ").replace(", ", " ").strip();
        return switch (normalized) {
            case "да", "применить", "да применить", "подтверждаю", "да подтверждаю" -> Intent.ACCEPT;
            case "нет", "отмена", "отменить", "нет отменить" -> Intent.DECLINE;
            default -> Intent.NEEDS_DIALOGUE;
        };
    }
}
