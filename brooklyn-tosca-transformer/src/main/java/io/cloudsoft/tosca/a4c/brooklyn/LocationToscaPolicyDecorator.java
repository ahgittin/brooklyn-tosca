package io.cloudsoft.tosca.a4c.brooklyn;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.camp.brooklyn.spi.creation.BrooklynYamlLocationResolver;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import alien4cloud.tosca.parser.impl.advanced.GroupPolicyParser;

public class LocationToscaPolicyDecorator extends AbstractToscaPolicyDecorator {

    private Map<String, EntitySpec<?>> specs;

    LocationToscaPolicyDecorator(Map<String, EntitySpec<?>> specs, ManagementContext mgmt) {
        super(mgmt);
        this.specs = specs;
    }

    public void decorate(Map<String, ?> policyData, String policyName, Optional<String> type, Set<String> groupMembers) {
        List<LocationSpec<?>> locations = getLocationSpecs(policyData);
        for (String id : groupMembers) {
            EntitySpec<?> spec = specs.get(id);
            if (spec == null) {
                throw new IllegalStateException("No node " + id + " found, when setting locations");
            }
            spec.locationSpecs(locations);
        }
    }

    private List<LocationSpec<?>> getLocationSpecs(Map<String, ?> policyData) {
        Object data = policyData.containsKey(GroupPolicyParser.VALUE)
                ? policyData.get(GroupPolicyParser.VALUE)
                : getToscaObjectPropertiesExtendedExcluding(policyData, true, true, POLICY_FLAG_NAME, POLICY_FLAG_TYPE);
        return resolveLocationSpecs(ImmutableMap.of("location", data));
    }

    private List<LocationSpec<?>> resolveLocationSpecs(Map<String, ?> locations) {
        return new BrooklynYamlLocationResolver(mgmt).resolveLocations(locations, true);
    }
}
