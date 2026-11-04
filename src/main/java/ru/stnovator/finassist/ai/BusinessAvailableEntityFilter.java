package ru.stnovator.finassist.ai;

import io.jmix.aitools.dataload.introspection.impl.DefaultAvailableEntityFilter;
import io.jmix.aitools.dataload.introspection.model.EntityDescriptor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class BusinessAvailableEntityFilter extends DefaultAvailableEntityFilter {
    private final BusinessEntityRegistry registry;

    public BusinessAvailableEntityFilter(BusinessEntityRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<EntityDescriptor> filter(List<EntityDescriptor> descriptors) {
        return super.filter(descriptors.stream().filter(d -> registry.find(d.getName()).isPresent()).toList());
    }
}
