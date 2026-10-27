package ru.stnovator.finassist.view.support;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.details.Details;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.action.SecuredBaseAction;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.combobox.EntityComboBox;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.valuepicker.EntityPicker;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewControllerUtils;
import org.springframework.stereotype.Service;

/** Presentation shared by the existing detail editors; never changes entity ownership or values. */
@Service
public class DetailViewPresentation {
    private final MetadataTools metadataTools;
    private final Messages messages;

    public DetailViewPresentation(MetadataTools metadataTools, Messages messages) {
        this.metadataTools = metadataTools;
        this.messages = messages;
    }

    public void initialize(StandardDetailView<?> view) {
        if (view.getElement().hasAttribute("data-detail-initialized")) {
            return;
        }
        view.getElement().setAttribute("data-detail-initialized", "true");
        for (Component component : UiComponentUtils.getComponents(view)) {
            if (component instanceof DataGrid<?> grid && grid.getItems() != null) {
                refreshGrid(grid);
                grid.getItems().addItemSetChangeListener(event -> refreshGrid(grid));
            }
            if (component instanceof EntityPicker<?> picker) {
                updateReference(picker);
                picker.addValueChangeListener(event -> updateReference(picker));
            }
            if (component instanceof EntityComboBox<?> comboBox) {
                comboBox.addClassName("detail-reference-field");
                updateReference(comboBox);
                comboBox.addValueChangeListener(event -> updateReference(comboBox));
            }
            if (component instanceof TypedTextField<?> field
                    && "nameField".equals(field.getId().orElse(null))) {
                updateName(field);
                field.addValueChangeListener(event -> updateName(field));
            }
        }
        UiComponentUtils.findComponent(view, "recordMetadata").ifPresent(metadata -> {
            TypedTextField<?> created = (TypedTextField<?>) UiComponentUtils.getComponent(view, "createdDateField");
            TypedTextField<?> modified = (TypedTextField<?>) UiComponentUtils.getComponent(view, "lastModifiedDateField");
            metadata.setVisible(created.getTypedValue() != null || modified.getTypedValue() != null);
            ((Details) metadata).setOpened(false);
        });
        UiComponentUtils.findComponent(view, "parentContextHint").ifPresent(hint -> {
            EntityPicker<?> parent = (EntityPicker<?>) UiComponentUtils.getComponent(view, "correctionField");
            hint.setVisible(parent.getValue() == null);
            SecuredBaseAction<?> save = (SecuredBaseAction<?>) ViewControllerUtils.getViewActions(view)
                    .getAction("saveAction");
            save.addEnabledRule(() -> parent.getValue() != null);
            save.refreshState();
        });
    }

    public void refreshGrid(DataGrid<?> grid) {
        if (grid.getItems() == null) {
            return;
        }
        int count = grid.getItems().getItems().size();
        // Keep virtualization: never switch to allRowsVisible for an unbounded collection.
        int headerAndFooter = grid.getFooterRows().isEmpty() ? 56 : 96;
        grid.setHeight((headerAndFooter + Math.max(2, Math.min(count, 8)) * 36) + "px");
        if (!grid.isEnabled()) {
            grid.setEmptyStateText(message("createScheduleFirst"));
        } else {
            grid.setEmptyStateText(message("emptyItems"));
        }
    }

    private void updateReference(EntityPicker<?> picker) {
        Object value = picker.getValue();
        String name = value == null ? "" : metadataTools.getInstanceName(value);
        // Helper text belongs to the secured field and inherits its visibility.
        picker.setHelperText(name.length() > 28 ? name : "");
        if (picker.isReadOnly()) {
            picker.setRequiredIndicatorVisible(false);
        }
    }

    private void updateReference(EntityComboBox<?> comboBox) {
        Object value = comboBox.getValue();
        String name = value == null ? "" : metadataTools.getInstanceName(value);
        comboBox.setHelperText(name.length() > 28 ? name : "");
    }

    private void updateName(TypedTextField<?> field) {
        String value = field.getValue();
        field.setHelperText(value.length() > 40 ? value : "");
    }

    private String message(String key) {
        return messages.getMessage("ru.stnovator.finassist.view.detail", key);
    }
}
