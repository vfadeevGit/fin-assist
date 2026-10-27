package ru.stnovator.finassist.view.main;

import io.jmix.flowui.backgroundtask.BackgroundTask;
import io.jmix.flowui.backgroundtask.BackgroundTaskHandler;
import io.jmix.flowui.backgroundtask.BackgroundWorker;
import io.jmix.flowui.backgroundtask.TaskLifeCycle;
import io.jmix.flowui.view.View;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.stnovator.finassist.service.DashboardCountService;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;

/** Per-view coordinator. All entry points and completion callbacks run on the UI thread. */
public class MainDashboardLoader {
    private static final Logger log = LoggerFactory.getLogger(MainDashboardLoader.class);
    private static final int COUNT_TIMEOUT_SECONDS = 30;
    private final BackgroundWorker worker;
    private final DashboardCountService service;
    private final Set<DashboardFeature> pending = EnumSet.noneOf(DashboardFeature.class);
    private final EnumMap<DashboardFeature, BackgroundTaskHandler<Long>> handlers =
            new EnumMap<>(DashboardFeature.class);
    private long generation;

    public MainDashboardLoader(BackgroundWorker worker, DashboardCountService service) {
        this.worker = worker;
        this.service = service;
    }

    public record CountResult(DashboardFeature feature, OptionalLong count) { }

    public boolean isLoading() {
        return !pending.isEmpty();
    }

    public boolean refresh(View<?> owner, Set<DashboardFeature> features,
                           Consumer<CountResult> onResult, Runnable onComplete) {
        if (isLoading()) {
            return false;
        }
        long currentGeneration = ++generation;
        pending.addAll(features);
        if (pending.isEmpty()) {
            onComplete.run();
            return true;
        }
        for (DashboardFeature feature : EnumSet.copyOf(features)) {
            if (generation != currentGeneration) {
                break;
            }
            BackgroundTask<Void, Long> task = new BackgroundTask<>(COUNT_TIMEOUT_SECONDS, owner) {
                @Override
                public Long run(TaskLifeCycle<Void> taskLifeCycle) {
                    return switch (feature) {
                        case CUSTOMERS -> service.countCustomers();
                        case CONTRACTS -> service.countContracts();
                        case PROJECTS -> service.countProjects();
                    };
                }

                private void settle(OptionalLong count) {
                    if (generation != currentGeneration || !pending.contains(feature)) {
                        return;
                    }
                    if (!owner.isAttached()) {
                        cancel();
                        return;
                    }
                    pending.remove(feature);
                    handlers.remove(feature);
                    onResult.accept(new CountResult(feature, count));
                    if (generation == currentGeneration && pending.isEmpty()) {
                        onComplete.run();
                    }
                }

                @Override
                public void done(Long result) {
                    settle(OptionalLong.of(result));
                }

                @Override
                public boolean handleException(Exception exception) {
                    log.warn("Unable to load dashboard count for {}", feature, exception);
                    settle(OptionalLong.empty());
                    return true;
                }

                @Override
                public boolean handleTimeoutException() {
                    log.warn("Dashboard count timed out for {}", feature);
                    settle(OptionalLong.empty());
                    return true;
                }

                @Override
                public void canceled() {
                    settle(OptionalLong.empty());
                }
            };
            try {
                BackgroundTaskHandler<Long> handler = worker.handle(task);
                handlers.put(feature, handler);
                handler.execute();
            } catch (RuntimeException exception) {
                task.handleException(exception);
            }
        }
        return true;
    }

    public void cancel() {
        generation++;
        var obsoleteHandlers = handlers.values().toArray(BackgroundTaskHandler[]::new);
        handlers.clear();
        pending.clear();
        for (BackgroundTaskHandler<?> handler : obsoleteHandlers) {
            handler.cancel();
        }
    }
}
