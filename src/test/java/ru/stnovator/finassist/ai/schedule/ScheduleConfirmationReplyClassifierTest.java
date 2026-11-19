package ru.stnovator.finassist.ai.schedule;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static ru.stnovator.finassist.ai.schedule.ScheduleConfirmationReplyClassifier.*;

class ScheduleConfirmationReplyClassifierTest {
    @Test void recognizesOnlyCompleteStatements() {
        assertThat(classify("  ДА,   применить! ")).isEqualTo(Intent.ACCEPT);
        assertThat(classify("Нет.")).isEqualTo(Intent.DECLINE);
        for (String reply : new String[]{"да?", "«да»", "иногда", "да, но исправь сумму", "> да", "файл говорит: да", ""})
            assertThat(classify(reply)).as(reply).isEqualTo(Intent.NEEDS_DIALOGUE);
    }
}
