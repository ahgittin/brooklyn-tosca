package org.apache.brooklyn.tosca.a4c.brooklyn;

import java.util.Map;

import org.apache.brooklyn.api.catalog.BrooklynCatalog;
import org.apache.brooklyn.api.catalog.CatalogItem;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.catalog.internal.CatalogUtils;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.software.base.VanillaSoftwareProcess;
import org.apache.brooklyn.location.jclouds.JcloudsLocationConfig;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.flags.TypeCoercions;
import org.apache.brooklyn.util.text.Strings;
import org.apache.commons.lang3.StringUtils;
import org.jclouds.compute.domain.OsFamily;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import alien4cloud.model.components.AbstractPropertyValue;
import alien4cloud.model.components.ImplementationArtifact;
import alien4cloud.model.components.Interface;
import alien4cloud.model.components.Operation;
import alien4cloud.model.components.ScalarPropertyValue;
import alien4cloud.model.topology.NodeTemplate;

public class ToscaNodeToEntityConverter {

    private static final Logger log = LoggerFactory.getLogger(ToscaNodeToEntityConverter.class);

    private final ManagementContext mgnt;
    private NodeTemplate nodeTemplate;
    private String nodeId;

    private ToscaNodeToEntityConverter(ManagementContext mgmt) {
        this.mgnt = mgmt;
    }

    public static ToscaNodeToEntityConverter with(ManagementContext mgmt) {
        return new ToscaNodeToEntityConverter(mgmt);
    }

    public ToscaNodeToEntityConverter setNodeTemplate(NodeTemplate nodeTemplate) {
        this.nodeTemplate = nodeTemplate;
        return this;
    }

    public ToscaNodeToEntityConverter setNodeId(String nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    public EntitySpec<? extends Entity> createSpec() {
        if (this.nodeTemplate == null) {
            throw new IllegalStateException("TOSCA node template is missing. You must specify it by using the method #setNodeTemplate(NodeTemplate nodeTemplate)");
        }
        if (StringUtils.isEmpty(this.nodeId)) {
            throw new IllegalStateException("TOSCA node ID is missing. You must specify it by using the method #setNodeId(String nodeId)");
        }

        EntitySpec<?> spec = null;

        CatalogItem<?, EntitySpec<?>> catalogItem = getEntityCatalogItem();
        if (catalogItem != null) {
            log.info("Found Brooklyn catalog item that match node type: " + this.nodeTemplate.getType());
            spec = (EntitySpec<?>) this.mgnt.getCatalog().createSpec((CatalogItem) catalogItem);
        } else {
            try {
                log.info("Found Brooklyn entity that match node type: " + this.nodeTemplate.getType());
                spec = EntitySpec.create((Class<? extends Entity>) Class.forName(this.nodeTemplate.getType()));
            } catch (ClassNotFoundException e) {
                log.info("Cannot find any Brooklyn catalog item nor Brooklyn entities that match node type: " +
                        this.nodeTemplate.getType() + ". Defaulting to a VanillaSoftwareProcess");
                spec = EntitySpec.create(VanillaSoftwareProcess.class);
            }
        }

        // Applying name from the node template or its ID
        if (Strings.isNonBlank(this.nodeTemplate.getName())) {
            spec.displayName(this.nodeTemplate.getName());
        } else {
            spec.displayName(this.nodeId);
        }
        // Add TOSCA node type as a property
        spec.configure("tosca.node.type", this.nodeTemplate.getType());

        Map<String, AbstractPropertyValue> properties = this.nodeTemplate.getProperties();

        // Applying provisioning properties
        ConfigBag prov = ConfigBag.newInstance();
        prov.putIfNotNull(JcloudsLocationConfig.MIN_RAM, resolve(properties, "mem_size"));
        prov.putIfNotNull(JcloudsLocationConfig.MIN_DISK, resolve(properties, "disk_size"));
        prov.putIfNotNull(JcloudsLocationConfig.MIN_CORES, TypeCoercions.coerce(resolve(properties, "num_cpus"), Integer.class));
        prov.putIfNotNull(JcloudsLocationConfig.OS_FAMILY, TypeCoercions.coerce(resolve(properties, "os_distribution"), OsFamily.class));
        prov.putIfNotNull(JcloudsLocationConfig.OS_VERSION_REGEX, resolve(properties, "os_version"));
        // TODO: Mapping for "os_arch" and "os_type" are missing
        spec.configure(SoftwareProcess.PROVISIONING_PROPERTIES, prov.getAllConfig());

        // Adding remaining TOSCA properties as EntitySpec properties
        for (Map.Entry<String, AbstractPropertyValue> property : properties.entrySet()) {
            if (property.getValue() instanceof ScalarPropertyValue) {
                spec.configure(property.getKey(), ((ScalarPropertyValue) property.getValue()).getValue());
            }
        }

        // If the entity spec is of type VanillaSoftwareProcess, we assume that it's running. The operations should
        // then take care of setting up the correct scripts.
        if (spec.getType().isAssignableFrom(VanillaSoftwareProcess.class)) {
            spec.configure(VanillaSoftwareProcess.LAUNCH_COMMAND, "true");
            spec.configure(VanillaSoftwareProcess.STOP_COMMAND, "true");
            spec.configure(VanillaSoftwareProcess.CHECK_RUNNING_COMMAND, "true");
        }

        // Applying operations
        final Map<String, Operation> operations = getInterfaceOperations();
        if (!operations.isEmpty()) {
            if (!spec.getType().isAssignableFrom(VanillaSoftwareProcess.class)) {
                throw new IllegalStateException("Brooklyn entity: " + spec.getImplementation() +
                        " does not support interface operations defined by node template" + this.nodeTemplate.getType());
            }

            applyLifecycle(operations, "create", spec, VanillaSoftwareProcess.INSTALL_COMMAND);
            applyLifecycle(operations, "configure", spec, VanillaSoftwareProcess.CUSTOMIZE_COMMAND);
            applyLifecycle(operations, "start", spec, VanillaSoftwareProcess.LAUNCH_COMMAND);
            applyLifecycle(operations, "stop", spec, VanillaSoftwareProcess.STOP_COMMAND);

            if (!operations.isEmpty()) {
                log.warn("Could not translate some operations for " + this.nodeId + ": " + operations.keySet());
            }
        }

        return spec;
    }

    protected CatalogItem<?, EntitySpec<?>> getEntityCatalogItem() {
        if (CatalogUtils.looksLikeVersionedId(this.nodeTemplate.getType())) {
            String id = CatalogUtils.getIdFromVersionedId(this.nodeTemplate.getType());
            String version = CatalogUtils.getVersionFromVersionedId(this.nodeTemplate.getType());
            return (CatalogItem<?, EntitySpec<?>>) this.mgnt.getCatalog().getCatalogItem(id, version);
        } else {
            return (CatalogItem<?, EntitySpec<?>>) this.mgnt.getCatalog().getCatalogItem(this.nodeTemplate.getType(), BrooklynCatalog.DEFAULT_VERSION);
        }
    }

    protected Map<String, Operation> getInterfaceOperations() {
        Map<String, Operation> operations = MutableMap.of();

        // then get interface operations from node template
        if (this.nodeTemplate.getInterfaces() != null) {
            MutableMap<String, Interface> ifs = MutableMap.copyOf(this.nodeTemplate.getInterfaces());
            Interface ifa = null;
            if (ifa == null) {
                ifa = ifs.remove("tosca.interfaces.node.lifecycle.Standard");
            }
            if (ifa == null) {
                ifa = ifs.remove("standard");
            }
            if (ifa == null) {
                ifs.remove("Standard");
            }

            if (ifa!=null) {
                operations.putAll(ifa.getOperations());
            }

            if (!ifs.isEmpty()) {
                log.warn("Could not translate some interfaces for " + this.nodeId + ": " + ifs.keySet());
            }
        }

        return operations;
    }

    protected void applyLifecycle(Map<String, Operation> ops, String opKey, EntitySpec<? extends Entity> spec, ConfigKey<String> cmdKey) {
        Operation op = ops.remove(opKey);
        if (op == null) {
            return;
        }
        ImplementationArtifact artifact = op.getImplementationArtifact();
        if (artifact != null) {
            String ref = artifact.getArtifactRef();
            if (ref != null) {
                // TODO get script/artifact relative to CSAR
                String script = new ResourceUtils(this).getResourceAsString(ref);
                String setScript = (String) spec.getConfig().get(cmdKey);
                if (Strings.isBlank(setScript) || setScript.trim().equals("true")) {
                    setScript = script;
                } else {
                    setScript += "\n"+script;
                }
                spec.configure(cmdKey, setScript);
                return;
            }
            log.warn("Unsupported operation implementation for " + opKey + ": " + artifact + " has no ref");
            return;
        }
        log.warn("Unsupported operation implementation for " + opKey + ": " + artifact + " has no impl");

    }

    public static String resolve(Map<String, AbstractPropertyValue> props, String... keys) {
        for (String key: keys) {
            AbstractPropertyValue v = props.remove(key);
            if (v == null) {
                continue;
            }
            if (v instanceof ScalarPropertyValue) {
                return ((ScalarPropertyValue)v).getValue();
            }
            log.warn("Ignoring unsupported property value " + v);
        }
        return null;
    }
}
