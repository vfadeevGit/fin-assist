package ru.stnovator.finassist.view.support;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.button.Button;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.grid.headerfilter.DataGridHeaderFilter;
import io.jmix.flowui.component.propertyfilter.PropertyFilter;
import io.jmix.flowui.model.CollectionLoader;
import java.util.List;

/** Coordinates native header filters without changing the generic filter's conditions. */
public final class ColumnFilterSupport {
    private ColumnFilterSupport() {}

    private record FilterState(PropertyFilter.Operation operation, Object value) {}
    private static final class Binding {
        final CollectionLoader<?> loader;
        List<FilterState> applied;
        boolean clearing;
        Binding(CollectionLoader<?> loader, List<FilterState> applied) {
            this.loader = loader;
            this.applied = applied;
        }
    }

    /** Bind after URL state has been restored, so a return to the list keeps its page. */
    public static void bind(DataGrid<?> grid, CollectionLoader<?> loader, Button reset) {
        if (ComponentUtil.getData(grid, Binding.class) != null) return;
        Binding binding = new Binding(loader, snapshot(grid));
        ComponentUtil.setData(grid, Binding.class, binding);
        loader.addPreLoadListener(event -> {
            if (binding.clearing) {
                event.preventLoad();
                return;
            }
            List<FilterState> current = snapshot(grid);
            if (!current.equals(binding.applied)) {
                loader.setFirstResult(0);
                // The load context has already been built when PreLoadEvent is delivered.
                event.getLoadContext().getQuery().setFirstResult(0);
                binding.applied = current;
            }
        });
        reset.addClickListener(event -> clearColumnFilters(grid));
    }

    public static void clearColumnFilters(DataGrid<?> grid) {
        Binding binding = ComponentUtil.getData(grid, Binding.class);
        if (binding == null) throw new IllegalStateException("Column filters have not been bound");
        List<DataGridHeaderFilter> headers = headers(grid);
        binding.clearing = true;
        try {
            // apply() also clears the native Cancel baseline and funnel indicator.
            // Suppress intermediate queries, then load the combined result once.
            headers.forEach(header -> header.getPropertyFilter().clear());
            headers.forEach(DataGridHeaderFilter::apply);
        } finally {
            binding.clearing = false;
        }
        binding.loader.load();
        if (!headers.isEmpty()) {
            var header = headers.getFirst();
            ComponentUtil.fireEvent(header,
                    new DataGridHeaderFilter.ApplyEvent(header, header.getPropertyFilter(), false));
        }
    }

    private static List<DataGridHeaderFilter> headers(DataGrid<?> grid) {
        return grid.getColumns().stream().map(column -> column.getHeaderComponent())
                .filter(DataGridHeaderFilter.class::isInstance).map(DataGridHeaderFilter.class::cast).toList();
    }

    private static List<FilterState> snapshot(DataGrid<?> grid) {
        return headers(grid).stream().map(header -> {
            var filter = header.getPropertyFilter();
            Object value = filter.getValue();
            return new FilterState(filter.getOperation(), value instanceof List<?> list ? List.copyOf(list) : value);
        }).toList();
    }
}
