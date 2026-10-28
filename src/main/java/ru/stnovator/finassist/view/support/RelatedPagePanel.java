package ru.stnovator.finassist.view.support;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import io.jmix.flowui.backgroundtask.*;
import io.jmix.flowui.view.View;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;

/** One expanded row. Owns only one page and cancels work when the row disappears. */
public class RelatedPagePanel<T> extends VerticalLayout {
    public record Result<T>(List<T> rows, boolean hasNext, Runnable renderFooter) {}
    private final Grid<T> grid;
    private final BackgroundWorker worker;
    private final View<?> owner;
    private final IntFunction<Result<T>> load;
    private final Consumer<Integer> pageChanged;
    private final Function<String,String> messages;
    private final Span status = new Span();
    private final Span pageLabel = new Span();
    private final Button previous;
    private final Button next;
    private final Button retry;
    private BackgroundTaskHandler<Result<T>> handler;
    private int page;
    private long generation;
    private boolean loaded;

    public RelatedPagePanel(View<?> owner, String id, String title, Grid<T> grid, int page,
                            BackgroundWorker worker, IntFunction<Result<T>> load, Consumer<Integer> pageChanged,
                            Function<String,String> messages) {
        this.owner=owner; this.grid=grid; this.page=page; this.worker=worker; this.load=load;
        this.pageChanged=pageChanged; this.messages=messages;
        setId(id); setWidthFull(); setPadding(true); setSpacing(false);
        addClassName("related-records-panel");
        getElement().setAttribute("role","region"); getElement().setAttribute("aria-label",title);
        grid.setId(id+"-grid"); grid.setWidthFull(); grid.setHeight("18rem");
        grid.getElement().setAttribute("aria-label",title);
        status.getElement().setAttribute("role","status");
        previous=new Button(messages.apply("previous"), e -> { this.page--; reload(); });
        next=new Button(messages.apply("next"), e -> { this.page++; reload(); });
        retry=new Button(messages.apply("retry"), e -> reload());
        previous.setId(id+"-previous"); next.setId(id+"-next"); retry.setId(id+"-retry");
        add(new H4(title),status,grid,new HorizontalLayout(previous,pageLabel,next,retry));
        addAttachListener(e -> { if(!loaded) reload(); });
        addDetachListener(e -> { cancel(); loaded=false; });
    }

    public void setAccessibleName(String name) {
        getElement().setAttribute("aria-label",name);
        grid.getElement().setAttribute("aria-label",name);
    }

    public void reload() {
        cancel(); loaded=true;
        long request=++generation;
        int requestedPage=page;
        status.setText(messages.apply("loading")); grid.setVisible(false);
        previous.setEnabled(false); next.setEnabled(false); retry.setVisible(false);
        BackgroundTask<Void,Result<T>> task = new BackgroundTask<>(60,owner) {
            @Override public Result<T> run(TaskLifeCycle<Void> lifeCycle) { return load.apply(requestedPage); }
            @Override public void done(Result<T> result) {
                if(request!=generation || !isAttached()) return;
                handler=null;
                if(result.rows().isEmpty() && page>0) { page--; reload(); return; }
                grid.setItems(result.rows()); result.renderFooter().run(); grid.setVisible(true);
                status.setText(result.rows().isEmpty()?messages.apply("empty"):"");
                previous.setEnabled(page>0); next.setEnabled(result.hasNext());
                pageLabel.setText(messages.apply("page")+" "+(page+1)); pageChanged.accept(page);
            }
            @Override public boolean handleException(Exception exception) {
                LoggerFactory.getLogger(RelatedPagePanel.class).warn("Cannot load related page",exception);
                failed(request); return true;
            }
            @Override public boolean handleTimeoutException() { failed(request); return true; }
        };
        try { handler=worker.handle(task); handler.execute(); }
        catch(RuntimeException exception) { task.handleException(exception); }
    }

    private void failed(long request) {
        if(request!=generation || !isAttached()) return;
        handler=null; status.setText(messages.apply("unavailable")); retry.setVisible(true);
    }

    public void cancel() {
        generation++;
        if(handler!=null) { var obsolete=handler; handler=null; obsolete.cancel(); }
    }
}
