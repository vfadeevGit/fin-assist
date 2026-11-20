package ru.stnovator.finassist.ai.view;

import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.streams.DownloadEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ContentDisposition;
import ru.stnovator.finassist.ai.schedule.ScheduleAttachmentService;
import ru.stnovator.finassist.ai.schedule.ScheduleAttachmentService.SubmittedAttachment;
import ru.stnovator.finassist.ai.schedule.ScheduleAttachmentService.SubmittedDownload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ScheduleAttachmentDownloadTest {
    final DownloadEvent event = mock(DownloadEvent.class);
    final VaadinResponse response = mock(VaadinResponse.class);
    final ScheduleAttachmentService attachments = mock(ScheduleAttachmentService.class);
    final UUID conversation = UUID.randomUUID();
    final byte[] bytes = {0, 1, -1};
    final ByteArrayInputStream input = spy(new ByteArrayInputStream(bytes));

    SubmittedAttachment submitted(String name) {
        var file = new SubmittedAttachment(UUID.randomUUID(), name, bytes.length, true, UUID.randomUUID(), null);
        when(event.getResponse()).thenReturn(response);
        when(attachments.openSubmitted(file.id(), conversation, file.sourceMessageId()))
                .thenReturn(new SubmittedDownload(input, name, bytes.length));
        return file;
    }

    @ParameterizedTest
    @ValueSource(strings = {"W4 \"quoted\".xls", "График \"оплаты\".xls"})
    void dispositionEncodesTheOriginalFilenameAndTransfersExactBytes(String name) throws IOException {
        var output = new ByteArrayOutputStream();
        when(event.getOutputStream()).thenReturn(output);
        ScheduleAttachmentCard.download(event, attachments, conversation, submitted(name));
        var header = ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(eq("Content-Disposition"), header.capture());
        assertThat(header.getValue()).contains("filename*=UTF-8''").contains("%22");
        assertThat(ContentDisposition.parse(header.getValue()).getFilename()).isEqualTo(name);
        assertThat(output.toByteArray()).isEqualTo(bytes);
        verify(input).close();
    }

    @Test void inputClosesIfResponseOutputCannotBeAcquired() throws IOException {
        var file = submitted("schedule.xls");
        when(event.getOutputStream()).thenThrow(new UncheckedIOException(new IOException("Disconnected")));
        assertThatThrownBy(() -> ScheduleAttachmentCard.download(event, attachments, conversation, file))
                .isInstanceOf(UncheckedIOException.class);
        verify(input).close();
    }
}
