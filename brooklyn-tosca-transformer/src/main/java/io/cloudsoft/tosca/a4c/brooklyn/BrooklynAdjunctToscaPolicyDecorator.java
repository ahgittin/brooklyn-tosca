package io.cloudsoft.tosca.a4c.brooklyn;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.classloading.BrooklynClassLoadingContext;
import org.apache.brooklyn.camp.brooklyn.BrooklynCampReservedKeys;
import org.apache.brooklyn.camp.brooklyn.spi.creation.BrooklynEntityDecorationResolver;
import org.apache.brooklyn.camp.brooklyn.spi.creation.BrooklynYamlTypeInstantiator;
import org.apache.brooklyn.camp.brooklyn.spi.creation.BrooklynYamlTypeInstantiator.Factory;
import org.apache.brooklyn.core.mgmt.classloading.JavaBrooklynClassLoadingContext;
import org.apache.brooklyn.util.core.config.ConfigBag;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import io.cloudsoft.tosca.a4c.brooklyn.util.EntitySpecs;

public class BrooklynAdjunctToscaPolicyDecorator extends AbstractToscaPolicyDecorator {

    protected EntitySpec<? extends Application> rootSpec;
    private Function<Factory, BrooklynEntityDecorationResolver<?>> resolverFactory;
    private String brooklynYamlKey;

    public static BrooklynAdjunctToscaPolicyDecorator forPolicies(EntitySpec<? extends Application> rootSpec, ManagementContext mgmt) {
        return new BrooklynAdjunctToscaPolicyDecorator(rootSpec, mgmt, 
            BrooklynCampReservedKeys.BROOKLYN_POLICIES, BrooklynEntityDecorationResolver.PolicySpecResolver::new);
    }
    public static BrooklynAdjunctToscaPolicyDecorator forEnrichers(EntitySpec<? extends Application> rootSpec, ManagementContext mgmt) {
        return new BrooklynAdjunctToscaPolicyDecorator(rootSpec, mgmt, 
            BrooklynCampReservedKeys.BROOKLYN_ENRICHERS, BrooklynEntityDecorationResolver.EnricherSpecResolver::new);
    }
    
    BrooklynAdjunctToscaPolicyDecorator(EntitySpec<? extends Application> rootSpec, ManagementContext mgmt,
        String brooklynYamlKey, Function<BrooklynYamlTypeInstantiator.Factory, BrooklynEntityDecorationResolver<?>> resolverFactory) {
        super(mgmt);
        this.brooklynYamlKey = brooklynYamlKey; 
        this.rootSpec = rootSpec;
        this.resolverFactory = resolverFactory;
    }

    public void decorate(Map<String, ?> policyData, String toscaPolicyName, Optional<String> type, Set<String> groupMembers) {
        if (!type.isPresent()) {
            throw new IllegalStateException("Type was not found for policy " + toscaPolicyName);
        }
        ConfigBag toscaPolicyDefinition = getBrooklynObjectDefinition(type.get(), policyData);
        decorateEntityBrooklynWithToscaPolicies(rootSpec, groupMembers, toscaPolicyDefinition, toscaPolicyName);
    }

    protected ConfigBag getBrooklynObjectDefinition(String type, Map<String, ?> toscaObjectData) {
        List<?> policies = ImmutableList.of(ImmutableMap.of(
                "type", type,
                BrooklynCampReservedKeys.BROOKLYN_CONFIG, getToscaObjectPropertiesExtendedExcluding(toscaObjectData, true, true, POLICY_FLAG_NAME, POLICY_FLAG_TYPE)
                )
        );
        Map<?, ?> policyDefinition = ImmutableMap.of(brooklynYamlKey, policies);
        return ConfigBag.newInstance(policyDefinition);
    }

    private void decorateEntityBrooklynWithToscaPolicies(EntitySpec<? extends Application> appSpec, Set<String> groupMembers, ConfigBag policyDefinition, String policyName){
        BrooklynClassLoadingContext loader = JavaBrooklynClassLoadingContext.create(mgmt);
        BrooklynYamlTypeInstantiator.Factory yamlLoader = new BrooklynYamlTypeInstantiator.Factory(loader, this);

        if (groupMembers.isEmpty()) {
            decorateWithSpec(yamlLoader, appSpec, policyDefinition);
            return;
        }

        for (String specId: groupMembers){
            EntitySpec<?> spec = EntitySpecs.findChildEntitySpecByPlanId(appSpec, specId);
            if (spec == null) {
                throw new IllegalStateException("Error: NodeTemplate " + specId +
                        " defined by policy/enricher " + policyName + " was not found");
            }
            decorateWithSpec(yamlLoader, spec, policyDefinition);
        }
    }

    protected void decorateWithSpec(BrooklynYamlTypeInstantiator.Factory yamlLoader, EntitySpec<?> spec, ConfigBag brooklynObjectDefinition) { 
        resolverFactory.apply(yamlLoader).decorate(spec, brooklynObjectDefinition, null);
    }
}
