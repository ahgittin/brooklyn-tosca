package io.cloudsoft.tosca.a4c.brooklyn;

import java.util.Map;

import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.util.collections.MutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;

import io.cloudsoft.tosca.a4c.brooklyn.spec.AbstractSpecModifier;

public abstract class AbstractToscaPolicyDecorator implements ToscaPolicyDecorator {

    private static final Logger log = LoggerFactory.getLogger(AbstractToscaPolicyDecorator.class);
    
    protected final ManagementContext mgmt;
    
    public AbstractToscaPolicyDecorator(ManagementContext mgmt) {
        this.mgmt = mgmt;
    }

    public Map<String, ?> getToscaObjectPropertiesExtended(Map<String, ?> toscaObjectData) {
        return getToscaObjectPropertiesExtendedExcluding(toscaObjectData, true, false, POLICY_FLAG_NAME, POLICY_FLAG_TYPE);
    }
    
    /**
     * Given a map eg of a tosca type,
     * return everything under the TOSCA "properties" key, merged on top of things at the root
     * apart from a few selected exceptions.
     * Putting things under "properties" is the recommended TOSCA way, but
     * (1) for legacy reasons (old blueprints), and (2) for cases where properties have to be strongly typed
     * but we don't want to declare everything, we also accept things at the root as "extended" properties.
     * @param mgmt 
     * @param toscaObjectData
     */
    @SuppressWarnings("unchecked")
    public Map<String, ?> getToscaObjectPropertiesExtendedExcluding(Map<String, ?> toscaObjectData, 
            boolean promoteToscaProperties, boolean promoteBrooklynConfig, String... keysToOmit){
        Map<String, Object> data = MutableMap.copyOf(toscaObjectData);
        for (String keyToOmit: keysToOmit) {
            data.remove(keyToOmit);
        }
        
        if (promoteToscaProperties) {
            Map<String,?> props = (Map<String,?>) data.remove(POLICY_FLAG_PROPERTIES);
            if (props!=null) {
                data.putAll(props);
            }
        }
        
        if (promoteBrooklynConfig) {
            Object brooklynConfig = data.remove(POLICY_FLAG_BROOKLYN_CONFIG);
            if (brooklynConfig!=null) {
                data.putAll((Map<String,?>)brooklynConfig);
            }
        }

        // evaluate DSL
        Optional<Object> resolved = AbstractSpecModifier.resolveBrooklynDslValue(data, Optional.absent(), mgmt, null);
        if (resolved.isPresent()) {
            return (Map<String, ?>) resolved.get();
        } else {
            log.warn("Unable to resolve "+data+" for TOSCA; ignoring, some DSL/suppliers may not be set");
            return data;
        }
    }
    
}
