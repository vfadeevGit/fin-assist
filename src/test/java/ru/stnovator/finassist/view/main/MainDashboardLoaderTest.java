package ru.stnovator.finassist.view.main;

import io.jmix.flowui.backgroundtask.*;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.stnovator.finassist.service.DashboardCountService;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MainDashboardLoaderTest {
    BackgroundWorker worker = mock(BackgroundWorker.class);
    DashboardCountService service = mock(DashboardCountService.class);
    View<?> owner = mock(View.class);
    List<BackgroundTask<?, Long>> tasks = new ArrayList<>();
    List<BackgroundTaskHandler<Long>> handlers = new ArrayList<>();
    List<MainDashboardLoader.CountResult> results = new ArrayList<>();
    AtomicInteger completed = new AtomicInteger();
    MainDashboardLoader loader;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        when(owner.isAttached()).thenReturn(true);
        when(worker.handle(any())).thenAnswer(invocation -> {
            tasks.add((BackgroundTask<?, Long>) invocation.getArgument(0));
            BackgroundTaskHandler<Long> handler = mock(BackgroundTaskHandler.class);
            handlers.add(handler);
            return handler;
        });
        loader = new MainDashboardLoader(worker, service);
    }
    boolean refresh(Set<DashboardFeature> features) {
        return loader.refresh(owner, features, results::add, completed::incrementAndGet);
    }
    void all() { assertThat(refresh(EnumSet.allOf(DashboardFeature.class))).isTrue(); }

    @Test void publishesIndependentResults() throws Exception {
        when(service.countCustomers()).thenReturn(0L);
        when(service.countContracts()).thenReturn(5L);
        when(service.countProjects()).thenReturn(7L);
        all();
        for (int i = 0; i < 3; i++) {
            assertThat(tasks.get(i).getOwnerView()).isSameAs(owner);
            assertThat(tasks.get(i).getTimeoutSeconds()).isEqualTo(30);
            tasks.get(i).done(tasks.get(i).run(mock(TaskLifeCycle.class)));
        }
        assertThat(results).extracting(r -> r.count().orElseThrow()).containsExactly(0L, 5L, 7L);
        assertThat(completed).hasValue(1);
        assertThat(loader.isLoading()).isFalse();
    }
    @Test void failureDoesNotDiscardSuccess() {
        all(); tasks.get(0).done(2L);
        assertThat(tasks.get(1).handleException(new IllegalStateException("test failure"))).isTrue();
        tasks.get(2).done(4L);
        assertThat(results).extracting(MainDashboardLoader.CountResult::count)
                .containsExactly(OptionalLong.of(2), OptionalLong.empty(), OptionalLong.of(4));
        assertThat(completed).hasValue(1);
    }
    @Test void timeoutSettlesOnlyItsFeature() {
        all(); assertThat(tasks.get(0).handleTimeoutException()).isTrue();
        tasks.get(0).done(99L); // Late delivery after timeout must be ignored.
        tasks.get(1).done(3L); tasks.get(2).done(4L);
        assertThat(results).hasSize(3);
        assertThat(results.getFirst().count()).isEmpty();
        assertThat(completed).hasValue(1);
    }
    @Test void rejectedSubmissionDoesNotLeaveLoadingStuck() {
        when(worker.handle(any())).thenThrow(new RejectedExecutionException("test saturation"));
        all();
        assertThat(results).hasSize(3).allMatch(r -> r.count().isEmpty());
        assertThat(loader.isLoading()).isFalse();
        assertThat(completed).hasValue(1);
    }
    @Test void duplicateRefreshDoesNotQueueWork() {
        all(); assertThat(refresh(EnumSet.allOf(DashboardFeature.class))).isFalse();
        assertThat(tasks).hasSize(3);
    }
    @Test void cancelSuppressesLateResults() {
        all(); loader.cancel();
        for (var task : tasks) { task.done(100L); task.handleTimeoutException(); task.canceled(); }
        assertThat(results).isEmpty(); assertThat(completed).hasValue(0);
        assertThat(loader.isLoading()).isFalse();
        handlers.forEach(h -> verify(h).cancel());
    }
    @Test void oldCompletionCannotOverwriteNewGeneration() {
        all(); var oldTasks = List.copyOf(tasks); loader.cancel();
        refresh(EnumSet.of(DashboardFeature.CUSTOMERS));
        oldTasks.forEach(t -> t.done(100L));
        tasks.get(3).done(7L);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().count()).hasValue(7L);
        assertThat(completed).hasValue(1);
    }
    @Test void detachedOwnerSuppressesCallbacks() {
        all(); when(owner.isAttached()).thenReturn(false); tasks.get(0).done(100L);
        assertThat(results).isEmpty(); assertThat(completed).hasValue(0);
        assertThat(loader.isLoading()).isFalse();
    }
    @Test void emptyFeatureSetSchedulesNothing() {
        assertThat(refresh(Set.of())).isTrue();
        verifyNoInteractions(worker); assertThat(completed).hasValue(1);
        assertThat(loader.isLoading()).isFalse();
    }
}
