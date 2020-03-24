package io.cloudsoft.tosca.a4c.brooklyn;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.classloading.BrooklynClassLoadingContext;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.methods.BrooklynDslCommon;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.entity.software.base.VanillaSoftwareProcess;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.net.Urls;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import alien4cloud.application.ApplicationService;
import alien4cloud.component.ICSARRepositorySearchService;
import alien4cloud.component.repository.ICsarRepositry;
import alien4cloud.deployment.DeploymentTopologyService;
import alien4cloud.model.application.Application;
import alien4cloud.model.components.AbstractPropertyValue;
import alien4cloud.model.components.AttributeDefinition;
import alien4cloud.model.components.CSARDependency;
import alien4cloud.model.components.ComplexPropertyValue;
import alien4cloud.model.components.ConcatPropertyValue;
import alien4cloud.model.components.Csar;
import alien4cloud.model.components.DeploymentArtifact;
import alien4cloud.model.components.FunctionPropertyValue;
import alien4cloud.model.components.IValue;
import alien4cloud.model.components.ImplementationArtifact;
import alien4cloud.model.components.IndexedArtifactToscaElement;
import alien4cloud.model.components.IndexedInheritableToscaElement;
import alien4cloud.model.components.IndexedRelationshipType;
import alien4cloud.model.components.Interface;
import alien4cloud.model.components.ListPropertyValue;
import alien4cloud.model.components.Operation;
import alien4cloud.model.components.ScalarPropertyValue;
import alien4cloud.model.deployment.DeploymentTopology;
import alien4cloud.model.templates.TopologyTemplate;
import alien4cloud.model.templates.TopologyTemplateVersion;
import alien4cloud.model.topology.AbstractTemplate;
import alien4cloud.model.topology.NodeTemplate;
import alien4cloud.model.topology.RelationshipTemplate;
import alien4cloud.model.topology.Requirement;
import alien4cloud.model.topology.Topology;
import alien4cloud.paas.IPaaSTemplate;
import alien4cloud.paas.function.FunctionEvaluator;
import alien4cloud.paas.model.PaaSNodeTemplate;
import alien4cloud.paas.model.PaaSRelationshipTemplate;
import alien4cloud.paas.model.PaaSTopology;
import alien4cloud.paas.plan.TopologyTreeBuilderService;
import alien4cloud.paas.plan.ToscaNodeLifecycleConstants;
import alien4cloud.paas.plan.ToscaRelationshipLifecycleConstants;
import alien4cloud.topology.TopologyServiceCore;
import alien4cloud.topology.TopologyTemplateVersionService;
import alien4cloud.tosca.normative.NormativeComputeConstants;
import alien4cloud.tosca.normative.ToscaFunctionConstants;
import alien4cloud.tosca.parser.ParsingResult;
import io.cloudsoft.tosca.a4c.brooklyn.spec.AbstractSpecModifier;
import io.cloudsoft.tosca.a4c.brooklyn.util.NodeTemplates;

@Component
public class Alien4CloudFacade implements ToscaFacade<Alien4CloudApplication> {

    private static final Logger LOG = LoggerFactory.getLogger(Alien4CloudFacade.class);
    public static final String COMPUTE_TYPE = NormativeComputeConstants.COMPUTE_TYPE;

    private static final Map<String, ConfigKey<String>> lifeCycleMapping = ImmutableMap.<String, ConfigKey<String>>builder()
            .put(ToscaRelationshipLifecycleConstants.PRE_CONFIGURE_SOURCE, VanillaSoftwareProcess.PRE_CUSTOMIZE_COMMAND)
            .put(ToscaRelationshipLifecycleConstants.POST_CONFIGURE_SOURCE, VanillaSoftwareProcess.POST_CUSTOMIZE_COMMAND)
            .put(ToscaRelationshipLifecycleConstants.PRE_CONFIGURE_TARGET, VanillaSoftwareProcess.PRE_CUSTOMIZE_COMMAND)
            .put(ToscaRelationshipLifecycleConstants.POST_CONFIGURE_TARGET, VanillaSoftwareProcess.POST_CUSTOMIZE_COMMAND)
            .put(ToscaNodeLifecycleConstants.CREATE, VanillaSoftwareProcess.INSTALL_COMMAND)
            .put(ToscaNodeLifecycleConstants.CONFIGURE, VanillaSoftwareProcess.CUSTOMIZE_COMMAND)
            .put(ToscaNodeLifecycleConstants.START, VanillaSoftwareProcess.LAUNCH_COMMAND)
            .put(ToscaNodeLifecycleConstants.STOP, VanillaSoftwareProcess.STOP_COMMAND)
            .build();

    private ICSARRepositorySearchService repositorySearchService;
    private TopologyTreeBuilderService treeBuilder;
    private ICsarRepositry csarFileRepository;
    private TopologyServiceCore topologyService;
    private TopologyTemplateVersionService topologyTemplateVersionService;
    private DeploymentTopologyService deploymentTopologyService;
    private ApplicationService applicationService;

    private final File tmpRoot;

    @Inject
    public Alien4CloudFacade(ICSARRepositorySearchService repositorySearchService, TopologyTreeBuilderService treeBuilder, ICsarRepositry csarFileRepository, TopologyServiceCore topologyService, TopologyTemplateVersionService topologyTemplateVersionService, DeploymentTopologyService deploymentTopologyService, ApplicationService applicationService) {
        this.repositorySearchService = repositorySearchService;
        this.treeBuilder = treeBuilder;
        this.csarFileRepository = csarFileRepository;
        this.topologyService = topologyService;
        this.topologyTemplateVersionService = topologyTemplateVersionService;
        this.deploymentTopologyService = deploymentTopologyService;
        this.applicationService = applicationService;

        tmpRoot = Os.newTempDir("brooklyn-a4c");
        Os.deleteOnExitRecursively(tmpRoot);
    }

    private Topology getTopologyOfCsar(Csar cs) {
        TopologyTemplate tt = topologyService.searchTopologyTemplateByName(cs.getName());
        if (tt == null) return null;
        TopologyTemplateVersion[] ttv = topologyTemplateVersionService.getByDelegateId(tt.getId());
        if (ttv == null || ttv.length == 0) return null;
        return topologyService.getTopology(ttv[0].getTopologyId());
    }

    /**
     * Resolves the parent dependency of the given node template.
     * <p>
     * The parent dependency is:
     * <ul>
     * <li>The target node of a requirement called "host"</li>
     * <li>The resolved value of a property called "host"</li>
     * <li>Null</li>
     * </ul>
     *
     * @param nodeId The node to examine
     * @param toscaApplication The tosca application
     * @return The id of the parent or null.
     */
    @Override
    public String getParentId(String nodeId, Alien4CloudApplication toscaApplication) {
        NodeTemplate nodeTemplate = toscaApplication.getNodeTemplate(nodeId);
        
        if (nodeTemplate==null) {
            throw new IllegalArgumentException("Node '"+nodeId+"' is not in the application");
        }
        
        Optional<Object> parentIdExplicit = resolveToscaScalarValueInMap(nodeTemplate.getProperties(), "parent");
        if (parentIdExplicit.isPresent()) {
            return (String) parentIdExplicit.get();
        }
        
        Requirement host = nodeTemplate.getRequirements() != null ? nodeTemplate.getRequirements().get("host") : null;
        if (host != null) {
            if (nodeTemplate.getRelationships()==null || nodeTemplate.getRelationships().values()==null) {
                LOG.warn("Host requirement found but relationships are null: "+host);
            } else {
                for (RelationshipTemplate r : nodeTemplate.getRelationships().values()) {
                    if (r.getRequirementName().equals("host")) {
                        return r.getTarget();
                    }
                }
                LOG.warn("Host requirement found but no corresponding relationship: "+host);
            }
        }

        // temporarily, fall back to looking for a *property* called 'host'
        // TODO: why?
        Optional<Object> parentId = resolveToscaScalarValueInMap(nodeTemplate.getProperties(), "host");
        if (parentId.isPresent()) {
            LOG.warn("Using legacy 'host' *property* to resolve host; use *requirement* instead.");
        }
        return (String) parentId.orNull();
    }

    private IndexedArtifactToscaElement getIndexedNodeTemplate(String nodeId, Alien4CloudApplication toscaApplication) {
        return repositorySearchService.getRequiredElementInDependencies(
                IndexedArtifactToscaElement.class,
                toscaApplication.getNodeTemplate(nodeId).getType(),
                toscaApplication.getTopology().getDependencies());
    }

    private Optional<IndexedArtifactToscaElement> getIndexedRelationshipTemplate(String nodeId, Alien4CloudApplication toscaApplication, String requirementId) {
        Optional<RelationshipTemplate> optionalRelationshipTemplate = findRelationshipRequirement(nodeId, toscaApplication, requirementId);
        if(optionalRelationshipTemplate.isPresent()) {
            RelationshipTemplate relationshipTemplate = optionalRelationshipTemplate.get();
            return Optional.<IndexedArtifactToscaElement>of(repositorySearchService.getRequiredElementInDependencies(
                    IndexedRelationshipType.class,
                    relationshipTemplate.getType(),
                    toscaApplication.getTopology().getDependencies()
            ));
        }

        return Optional.absent();
    }

    @Override
    public boolean isDerivedFrom(String nodeId, Alien4CloudApplication toscaApplication, String type) {
        NodeTemplate nodeTemplate = toscaApplication.getNodeTemplate(nodeId);
        return nodeTemplate.getType().equals(type) ||
                getIndexedNodeTemplate(nodeId, toscaApplication)
                        .getDerivedFrom()
                        .contains(type);
    }

    private Map<String, PaaSNodeTemplate> getAllNodes(Alien4CloudApplication toscaApplication) {
        return treeBuilder.buildPaaSTopology(toscaApplication.getTopology()).getAllNodes();
    }

    @Override
    public Map<String, Object> getTemplatePropertyObjects(String nodeId, Alien4CloudApplication toscaApplication, String computeName) {
        Map<String, PaaSNodeTemplate> builtPaaSNodeTemplates = getAllNodes(toscaApplication);
        PaaSNodeTemplate paasNodeTemplate = builtPaaSNodeTemplates.get(computeName);
        NodeTemplate nodeTemplate = toscaApplication.getNodeTemplate(nodeId);
        return getTemplatePropertyObjects(nodeTemplate, paasNodeTemplate, builtPaaSNodeTemplates, toscaApplication.getKeywordMap(nodeTemplate));
    }

    private Map<String, Object> getTemplatePropertyObjects(AbstractTemplate template, PaaSNodeTemplate paasNodeTemplate, Map<String, PaaSNodeTemplate> builtPaaSNodeTemplates, Map<String, String> keywordMap) {
        return getPropertyObjects(template.getProperties(), paasNodeTemplate, builtPaaSNodeTemplates, keywordMap);
    }

    private Map<String, Object> getPropertyObjects(Map<String, AbstractPropertyValue> propertyValueMap, PaaSNodeTemplate paasNodeTemplate, Map<String, PaaSNodeTemplate> builtPaaSNodeTemplates, Map<String, String> keywordMap) {
        Map<String, Object> propertyMap = MutableMap.of();
        for (String propertyKey : ImmutableSet.copyOf(propertyValueMap.keySet())) {
            propertyMap.put(propertyKey, resolveKeyIncludingToscaFunctions(propertyValueMap, propertyKey, paasNodeTemplate, builtPaaSNodeTemplates, keywordMap).orNull());
        }
        return propertyMap;
    }

    private Optional<Object> getToscaScalarValueUnlessItsAFunction(IValue v) {
        if (v instanceof ScalarPropertyValue) {
            return Optional.<Object>fromNullable(((ScalarPropertyValue) v).getValue());
        }
        if (v instanceof ComplexPropertyValue) {
            return Optional.<Object>fromNullable(((ComplexPropertyValue) v).getValue());
        }
        if (v instanceof ListPropertyValue) {
            return Optional.<Object>fromNullable(((ListPropertyValue) v).getValue());
        }
        if (!(v instanceof FunctionPropertyValue)) {
            LOG.warn("Ignoring unsupported property value " + v);
        }
        return Optional.absent();
    }

    private Optional<Object> resolveToscaScalarValueInMap(Map<String, ? extends IValue> props, String key) {
        IValue v = props.get(key);
        if (v == null) {
            LOG.trace("No value available for {}", key);
            return Optional.absent();
        }

        return getToscaScalarValueUnlessItsAFunction(v);
    }

    private Optional<Object> resolveIncludingToscaFunctions(IValue v,  IPaaSTemplate<? extends IndexedInheritableToscaElement> template, Map<String, PaaSNodeTemplate> builtPaaSNodeTemplates, Map<String, String> keywordMap) {
        Optional<Object> value = getToscaScalarValueUnlessItsAFunction(v);
        if (!value.isPresent()) {
            if (v instanceof FunctionPropertyValue) {
                FunctionPropertyValue functionPropertyValue = (FunctionPropertyValue) v;
                String node = Optional.fromNullable(keywordMap.get(functionPropertyValue.getTemplateName())).or(functionPropertyValue.getTemplateName());
                switch (functionPropertyValue.getFunction()) {
                    case ToscaFunctionConstants.GET_PROPERTY:
                        value = Optional.<Object>fromNullable(FunctionEvaluator.evaluateGetPropertyFunction(functionPropertyValue, template, builtPaaSNodeTemplates));
                        break;
                    case ToscaFunctionConstants.GET_ATTRIBUTE:
                        value = Optional.<Object>fromNullable(BrooklynDslCommon.entity(node).attributeWhenReady(functionPropertyValue.getElementNameToFetch()));
                        break;
                    case ToscaFunctionConstants.GET_INPUT:
                    case ToscaFunctionConstants.GET_OPERATION_OUTPUT:
                        // also "get_artifact" in recent TOSCA spec
                    default:
                        LOG.warn("TOSCA DSL function "+functionPropertyValue.getFunction()+" not supported, for "+v+" in "+template);
                        value = Optional.absent();
                }
            }
        }
        return value;
    }

    private Optional<Object> resolveKeyIncludingToscaFunctions(Map<String, ? extends IValue> props, String key, IPaaSTemplate<? extends IndexedInheritableToscaElement> template, Map<String, PaaSNodeTemplate> builtPaaSNodeTemplates, Map<String, String> keywordMap) {
        IValue v = props.get(key);
        if (v == null) {
            LOG.trace("No value available for {}", key);
            return Optional.absent();
        }
        return resolveIncludingToscaFunctions(v, template, builtPaaSNodeTemplates, keywordMap);
    }

    private Optional<Path> getCsarPath(DeploymentArtifact artifact) {
        return getCsarPath(artifact.getArchiveName(), artifact.getArchiveVersion());
    }

    @Override
    public Optional<Path> getCsarPath(String archiveName, String archiveVersion) {
        try {
            return Optional.of(csarFileRepository.getCSAR(archiveName, archiveVersion));
        } catch (Exception e) {
            return Optional.absent();
        }
    }

    private Optional<Map<String, DeploymentArtifact>> getArtifactsMap(String nodeId, Alien4CloudApplication toscaApplication) {
        Map<String, DeploymentArtifact> artifacts = toscaApplication.getNodeTemplate(nodeId).getArtifacts();
        if (artifacts == null || artifacts.isEmpty()) {
            return Optional.absent();
        }
        return Optional.of(artifacts);
    }

    @Override
    public Iterable<String> getArtifacts(String nodeId, Alien4CloudApplication toscaApplication) {
        Optional<Map<String, DeploymentArtifact>> optionalArtifacts = getArtifactsMap(nodeId, toscaApplication);
        if (!optionalArtifacts.isPresent()) {
            return Collections.emptyList();
        }
        return optionalArtifacts.get().keySet();
    }

    private Optional<DeploymentArtifact> getArtifact(String nodeId, Alien4CloudApplication toscaApplication, String artifactId) {
        return Optional.fromNullable(getArtifactsMap(nodeId, toscaApplication).get().get(artifactId));
    }

    private static final List<String> validInterfaceNames = ImmutableList.of("tosca.interfaces.node.lifecycle.Standard", "Standard", "standard");
    private Map<String, Operation> getStandardInterfaceOperationsMap(String nodeId, Alien4CloudApplication toscaApplication) {
        NodeTemplate nodeTemplate = toscaApplication.getNodeTemplate(nodeId);
        // or could getIndexedNodeTemplate(nodeId, toscaApplication) -- but above seems easier
        
        Optional<Interface> optionalNodeTemplateInterface = NodeTemplates.findInterfaceOfNodeTemplate(
                nodeTemplate.getInterfaces(), validInterfaceNames);

        if (optionalNodeTemplateInterface.isPresent()) {
            // merge is now done at build time so we no longer need to look at node types
            return optionalNodeTemplateInterface.get().getOperations();
        }
        
        return MutableMap.of();
    }

    private Map<String, Operation> getConfigureInterfaceOperationsMap(Alien4CloudApplication toscaApplication, ToscaApplication.Relationship relationship) {
        Map<String, Operation> operations = MutableMap.of();

        Optional<IndexedArtifactToscaElement> indexedRelationshipTemplate = Optional.<IndexedArtifactToscaElement>of(repositorySearchService.getRequiredElementInDependencies(
                IndexedRelationshipType.class,
                relationship.getRelationshipType(),
                toscaApplication.getTopology().getDependencies()
        ));

        if(!indexedRelationshipTemplate.isPresent()){
            return operations;
        }
        List<String> validInterfaceNames = ImmutableList.of("tosca.interfaces.relationship.Configure", "Configure", "configure");
        return getInterfaceOperationsMap(indexedRelationshipTemplate.get(), validInterfaceNames);
    }

    private Map<String, Operation> getInterfaceOperationsMap(IndexedArtifactToscaElement indexedRelationshipTemplate, List<String> validInterfaceNames) {
        Map<String, Operation> operations = MutableMap.of();
        Optional<Interface> optionalIndexedNodeTemplateInterface = NodeTemplates.findInterfaceOfNodeTemplate(
                indexedRelationshipTemplate.getInterfaces(),
                validInterfaceNames
        );
        if (!optionalIndexedNodeTemplateInterface.isPresent()) {
            return operations;
        }
        return MutableMap.copyOf(optionalIndexedNodeTemplateInterface.get().getOperations());
    }

    @Override
    public Iterable<String> getInterfaceOperations(String nodeId, Alien4CloudApplication toscaApplication) {
        return getStandardInterfaceOperationsMap(nodeId, toscaApplication).keySet();
    }

    @Override
    public Iterable<String> getInterfaceOperationsByRelationship(Alien4CloudApplication toscaApplication, ToscaApplication.Relationship relationship) {
        return getConfigureInterfaceOperationsMap(toscaApplication, relationship).keySet();
    }

    private Optional<PaaSNodeTemplate> getPaasNodeTemplate(String nodeId, Alien4CloudApplication toscaApplication) {
        NodeTemplate nodeTemplate = toscaApplication.getNodeTemplate(nodeId);
        PaaSTopology paaSTopology = treeBuilder.buildPaaSTopology(toscaApplication.getTopology());
        if (paaSTopology != null) {
            Map<String, PaaSNodeTemplate> builtPaaSNodeTemplates = paaSTopology.getAllNodes();
            String computeName = nodeTemplate.getName();
            return Optional.of(builtPaaSNodeTemplates.get(computeName));
        }
        return Optional.absent();
    }

    private Optional<RelationshipTemplate> findRelationshipRequirement(String nodeId, Alien4CloudApplication toscaApplication, String requirementId) {
        NodeTemplate node = toscaApplication.getNodeTemplate(nodeId);
        if (node.getRelationships() != null) {
            for (Map.Entry<String, RelationshipTemplate> entry : node.getRelationships().entrySet()) {
                if (entry.getValue().getRequirementName().equals(requirementId)) {
                    return Optional.of(entry.getValue());
                }
            }
        }
        LOG.warn("Requirement {} is not described by any relationship ", requirementId);
        return Optional.absent();
    }

    private Set<RelationshipTemplate> findRelationshipsRequirement(String nodeId, Alien4CloudApplication toscaApplication, String requirementId) {
        Set<RelationshipTemplate> result = MutableSet.of();
        NodeTemplate node = toscaApplication.getNodeTemplate(nodeId);
        if (node.getRelationships() != null) {
            for (Map.Entry<String, RelationshipTemplate> entry : node.getRelationships().entrySet()) {
                if ((entry.getValue() != null)
                        && entry.getValue().getRequirementName().equals(requirementId)) {
                    result.add(entry.getValue());
                }
            }
        }
        LOG.warn("Requirement {} is not described by any relationship ", requirementId);
        return result;
    }

    private Map<String, Object> getRelationProperties(String nodeId, String computeName, Alien4CloudApplication toscaApplication, RelationshipTemplate relationshipTemplate) {
        Map<String, PaaSNodeTemplate> builtPaaSNodeTemplates = getAllNodes(toscaApplication);
        PaaSNodeTemplate paasNodeTemplate = builtPaaSNodeTemplates.get(computeName);
        NodeTemplate nodeTemplate = toscaApplication.getNodeTemplate(nodeId);
        return getTemplatePropertyObjects(relationshipTemplate, paasNodeTemplate, builtPaaSNodeTemplates, toscaApplication.getKeywordMap(nodeTemplate, relationshipTemplate));
    }

    private Object resolveAttributeOrNullPossiblyWarning(Map.Entry<String, IValue> attribute, Alien4CloudApplication toscaApplication, String nodeId, PaaSNodeTemplate paaSNodeTemplate, Map<String, PaaSNodeTemplate> allNodes) {
        Map<String, PaaSNodeTemplate> builtPaaSNodeTemplates = getAllNodes(toscaApplication);
        PaaSNodeTemplate paasNodeTemplate = builtPaaSNodeTemplates.get(nodeId);
        // TODO can we get attribute value set in the node template?
        IValue attributeValue = attribute.getValue();
        if (attributeValue instanceof AttributeDefinition) {
            String defaultValue = ((AttributeDefinition) attribute.getValue()).getDefault();
            if (Strings.isBlank(defaultValue)) {
                return null;
            }
            return defaultValue;
        } else if (attributeValue instanceof ConcatPropertyValue) {
            return resolveConcat((ConcatPropertyValue) attributeValue, paasNodeTemplate, builtPaaSNodeTemplates, toscaApplication.getKeywordMap(nodeId));
        } else if (attributeValue instanceof FunctionPropertyValue) {
            Optional<Object> optionalResolvedAttribute = resolveIncludingToscaFunctions(attributeValue, paasNodeTemplate, builtPaaSNodeTemplates, toscaApplication.getKeywordMap(nodeId));
            return  optionalResolvedAttribute.orNull();
        } else {
            LOG.warn("Unable to resolve attribute {} of type {}", attribute.getKey(), attributeValue.getClass());
            return null;
        }
    }

    private Object resolveConcat(ConcatPropertyValue attributeValue, PaaSNodeTemplate paasNodeTemplate, Map<String, PaaSNodeTemplate> builtPaaSNodeTemplates, Map<String, String> keywordMap) {
        Object[] vals = new Object[attributeValue.getParameters().size()];
        for (int i = 0; i < attributeValue.getParameters().size(); i++) {
            IValue param = attributeValue.getParameters().get(i);
            Optional<Object> optionalResolvedAttribute = resolveIncludingToscaFunctions(param, paasNodeTemplate, builtPaaSNodeTemplates, keywordMap);
            vals[i] = optionalResolvedAttribute.or("");
        }

        String format = Strings.repeat("%s", vals.length);
        return BrooklynDslCommon.formatString(format, vals);
    }

    @Override
    public Object resolveProperty(String nodeId, Alien4CloudApplication toscaApplication, String key) {
        Map<String, AbstractPropertyValue> properties = toscaApplication.getNodeTemplate(nodeId).getProperties();
        return resolveToscaScalarValueInMap(properties, key).orNull();
    }

    @Override
    public Optional<Object> getScript(String opKey, String nodeId, Alien4CloudApplication toscaApplication, String computeName, String expandedFolder, @Nullable ManagementContext mgmt) {
        return new NodeScriptGenerator(opKey, nodeId, toscaApplication, computeName, expandedFolder).makeScript(mgmt);
    }

    @Override
    public Optional<Object> getRelationshipScript(String opKey, Alien4CloudApplication toscaApplication, ToscaApplication.Relationship relationship, String computeName, String expandedFolder, @Nullable ManagementContext mgmt) {
        return new RelationshipScriptGenerator(opKey, toscaApplication, relationship, computeName, expandedFolder).makeScript(mgmt);
    }

    @Override
    public ConfigKey<String> getLifeCycle(String opKey) {
        return lifeCycleMapping.get(opKey);
    }

    @Override
    public Map<String, Object> getResolvedAttributes(String nodeId, Alien4CloudApplication toscaApplication) {
        Map<String, Object> resolvedAttributes = MutableMap.of();
        Optional<PaaSNodeTemplate> optionalPaaSNodeTemplate = getPaasNodeTemplate(nodeId, toscaApplication);
        if (optionalPaaSNodeTemplate.isPresent()) {
            Map<String, PaaSNodeTemplate> allNodes = getAllNodes(toscaApplication);
            final Map<String, IValue> attributes = getIndexedNodeTemplate(nodeId, toscaApplication).getAttributes();
            for (Map.Entry<String, IValue> attribute : attributes.entrySet()) {
                String key = attribute.getKey().replaceAll("\\s+", ".");
                Object value = resolveAttributeOrNullPossiblyWarning(attribute, toscaApplication, nodeId, optionalPaaSNodeTemplate.get(), allNodes);
                resolvedAttributes.put(key, value);
            }
        }
        return resolvedAttributes;
    }

    @Override
    public Map<String, Object> getPropertiesAndTypeValuesByRelationshipId(String nodeId, Alien4CloudApplication toscaApplication, String relationshipId, String computeName) {
        Map<String, Object> result = MutableMap.of();
        RelationshipTemplate relationshipTemplate = toscaApplication.getNodeTemplate(nodeId).getRelationships().get(relationshipId);
        Preconditions.checkNotNull(relationshipTemplate, "Could not find relationship " + relationshipId + " on node " + nodeId);
        if (relationshipTemplate.getType().equals("brooklyn.relationships.Configure")) {
            Map<String, Object> relationProperties = getRelationProperties(nodeId, computeName, toscaApplication, relationshipTemplate);
            result = getPropertiesAndTypedValues(relationshipTemplate, relationProperties, computeName);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> joinPropertiesAndValueTypes(Map<String, Object> properties,
                                                            Map<String, Object> newProperties) {
        for(Map.Entry<String, Object> newPropertyEntry: newProperties.entrySet()) {
            String newPropertyKey = newPropertyEntry.getKey();
            Object newPropertyValue = newPropertyEntry.getValue();
            if (!properties.containsKey(newPropertyKey)) {
                properties.put(newPropertyKey, newPropertyValue);
            } else {
                Object oldPropertyValue = properties.get(newPropertyKey);
                if ((oldPropertyValue instanceof Map)
                        && (newPropertyValue instanceof Map)) {
                    ((Map<Object, Object>) oldPropertyValue).putAll((Map<?,?>) newPropertyValue);
                } else if ((oldPropertyValue instanceof List)
                        && (newPropertyValue instanceof List)) {
                    ((List<Object>) oldPropertyValue).addAll((List<?>) newPropertyValue);
                } else {
                    LOG.debug("New Property type {} can not be classified in {}, " +
                                    "it should be a Map or a List",
                            newPropertyValue.getClass(), this);
                }
            }
        }
        return properties;
    }

    private Map<String, Object> getPropertiesAndTypedValues(RelationshipTemplate relationshipTemplate, Map<String, Object> relationProperties, String nodeName) {
        // TODO: Use target properly.
        String target = relationshipTemplate.getTarget();
        String propName = (relationProperties.get("prop.name") != null) ? relationProperties.get("prop.name").toString() : Strings.EMPTY;
        String propCollection  = (relationProperties.get("prop.collection") != null) ? relationProperties.get("prop.collection").toString() : Strings.EMPTY;
        String propValue  = (relationProperties.get("prop.value") != null) ? relationProperties.get("prop.value").toString() : Strings.EMPTY;

        if (Strings.isBlank(propCollection) && (Strings.isBlank(propName))) {
            throw new IllegalStateException("Relationship for Requirement "
                    + relationshipTemplate.getRequirementName() + " on NodeTemplate "
                    + nodeName + ". Collection Name or Property Name should" +
                    " be defined for RelationsType " + relationshipTemplate.getType());
        }

        return (Strings.isBlank(propName)) ? MutableMap.<String, Object>of(propCollection, MutableList.of(propValue))
                : MutableMap.<String, Object>of(propCollection, MutableMap.of(propName, propValue));
    }

    @Override
    public String getArtifactRef(String nodeId, Alien4CloudApplication toscaApplication, String artifactId) {
        return getArtifact(nodeId, toscaApplication, artifactId).get().getArtifactRef();
    }
    
    @Override
    public Optional<Path> getArtifactPath(String nodeId, Alien4CloudApplication toscaApplication, String artifactId) {
        Optional<DeploymentArtifact> optionalArtifact = getArtifact(nodeId, toscaApplication, artifactId);
        if (!optionalArtifact.isPresent()) return Optional.absent();

        DeploymentArtifact artifact = optionalArtifact.get();
        Set<Optional<Path>> csarPaths = Sets.newLinkedHashSet();
        csarPaths.add(getCsarPath(artifact));
        for (CSARDependency d: toscaApplication.getTopology().getDependencies()) {
            csarPaths.add(getCsarPath(d.getName(), d.getVersion()));
        }

        for (Optional<Path> csarPath: csarPaths) {
            if (csarPath.isPresent()) {
                // found the path to the CSAR, check if file is there; if not, traverse dependencies
                Path candidate = Paths.get(csarPath.get().getParent().toAbsolutePath().toString(), "expanded", artifact.getArtifactRef());
                if (candidate.toFile().exists()) {
                    return Optional.of(candidate);
                }
            }
        }
        
        LOG.warn("Cannot find artifact '"+artifact.getArtifactRef()+"' in "+csarPaths);
        return Optional.absent();
    }

    private Alien4CloudApplication newToscaApplication(Csar csar) {
        return new Alien4CloudApplication(csar.getName(), getTopologyOfCsar(csar), "", csar);
    }

    @Override
    public Alien4CloudApplication newToscaApplication(String id) {
        DeploymentTopology deploymentTopology = deploymentTopologyService.getOrFail(id);
        Application application = applicationService.getOrFail(deploymentTopology.getDelegateId());
        return new Alien4CloudApplication(application.getName(), deploymentTopology, id,
            null /* TODO is there a way to find the containing CSAR; will things in topologies in here break without it? */);
    }

    @Override
    public Alien4CloudApplication parsePlan(String plan, Uploader uploader, BrooklynClassLoadingContext context) {
        ParsingResult<Csar> tp = new ToscaParser(uploader).parse(plan, context);
        Csar csar = tp.getResult();
        return newToscaApplication(csar);
    }

    @Override
    public Alien4CloudApplication parsePlan(Path path, Uploader uploader) {
        ParsingResult<Csar> tp = uploader.uploadArchive(path.toFile(), "submitted-tosca-archive");
        Csar csar = tp.getResult();
        return newToscaApplication(csar);
    }

    abstract class ScriptGenerator {

        protected final String opKey;
        protected final String nodeId;
        protected final Alien4CloudApplication toscaApplication;
        protected final String computeName;
        protected final String expandedFolder;

        public ScriptGenerator(String opKey, String nodeId, Alien4CloudApplication toscaApplication, String computeName, String expandedFolder) {
            this.opKey = opKey;
            this.nodeId = nodeId;
            this.toscaApplication = toscaApplication;
            this.computeName = computeName;
            this.expandedFolder = expandedFolder;
        }

        public Optional<Object> makeScript(@Nullable ManagementContext mgmt) {
            if (!lifeCycleMapping.containsKey(opKey)) {
                if (LOG.isTraceEnabled()) {
                    // normal if lifecycle not defined
                    LOG.trace("Could not translate operation, {}, for node template, {}.", opKey, toscaApplication.getNodeName(nodeId).orNull());
                }
                return Optional.absent();
            }

            Operation op = getOperation();
            ImplementationArtifact artifact = op.getImplementationArtifact();
            if (artifact == null) {
                if (LOG.isTraceEnabled()) {
                    // normal, at least for relationships
                    LOG.trace("Unsupported operation implementation for " + op.getDescription() + " on "+nodeId+":  lifecycle is defined but no artifact set");
                }
                return Optional.absent();
            }

            String ref = artifact.getArtifactRef();
            if (ref == null) {
                LOG.warn("Unsupported operation implementation for " + op.getDescription() + " on "+nodeId+": " + artifact + " is defined but has no ref");
                return Optional.absent();
            }
            return Optional.of(getScript(artifact, op, mgmt));
        }

        abstract Object getScript(ImplementationArtifact artifact, Operation op, @Nullable ManagementContext mgmt);

        abstract Operation getOperation();

        protected String getScript(ImplementationArtifact artifact) {
            // Trying to get the CSAR file based on the artifact reference. If it fails, then we try to get the
            // content of the script from any resources
            String artifactRef = artifact.getArtifactRef();
            String proto = Urls.getProtocol(artifactRef);
            if ("classpath".equals(proto)) {
                proto = null;
                artifactRef = Strings.removeAllFromStart(artifactRef, "classpath:", "/");
                // recast classpath URLs as things within the CSAR
            }
            Optional<Path> csarPath = Optional.absent();
            
            if (proto==null) {
                // not a URL; look in archive
                
                csarPath = getCsarPath(artifact.getArchiveName(), artifact.getArchiveVersion());
                if (!csarPath.isPresent() && toscaApplication.getArchive()!=null) {
                    // archive name / version are null in some cases, because the ArchivePostProcessor doesn't
                    // properly initialize ImplArts _inside_ topologies. we record the archive on the app 
                    // but in _some_ call paths only
                    csarPath = getCsarPath(toscaApplication.getArchive().getName(), toscaApplication.getArchive().getVersion());
                }
                if (csarPath.isPresent()) {
                    if (new File(csarPath.get().getParent().toString() + expandedFolder + artifactRef).exists()) {
                        return new ResourceUtils(this).getResourceAsString(csarPath.get().getParent().toString() + expandedFolder + artifactRef);
                    }
                }
            }
            // the brooklyn entity's bundle may also be a valid search path;
            // ideally build up a search path including the folder above if found
            // (if reading a tosca yaml then it would be valid not to have one however, 
            // so could remove the above error eg if it's a tosca yaml in a brooklyn bundle),
            // and in the call below use that search sequence
            try {
                return new ResourceUtils(this).getResourceAsString(artifactRef);
            } catch (RuntimeException e) {
                LOG.warn("Could not find "+artifactRef+" (rethrowing); "+
                        (csarPath.isPresent() ? "csar found as "+csarPath : proto==null ? "csar not found" : "context not set")
                        +": "+e);
                throw e;
            }
        }

        protected Optional<Object> buildExportStatements(Operation op, String script, @Nullable ManagementContext mgmt) {
            Map<String, PaaSNodeTemplate> builtPaaSNodeTemplates = getAllNodes(toscaApplication);
            PaaSNodeTemplate paasNodeTemplate = builtPaaSNodeTemplates.get(computeName);
            Map<String, IValue> inputParameters = op.getInputParameters();
            if (inputParameters == null) {
                return Optional.absent();
            }
            List<Object> dsls = Lists.newArrayList();
            for (Map.Entry<String, IValue> entry : inputParameters.entrySet()) {
                Optional<Object> value = resolveIncludingToscaFunctions(inputParameters, entry.getKey(), paasNodeTemplate, builtPaaSNodeTemplates);
                if (value.isPresent() && !Strings.isBlank(entry.getKey())) {
                    Object v = value.get();
                    v = AbstractSpecModifier.resolveBrooklynDslValue(v, Optional.absent(), mgmt, null).orNull();
                    dsls.add(BrooklynDslCommon.formatString("export %s=\"%s\"", entry.getKey(), value.get()));
                }
            }
            dsls.add(script);
            return Optional.of(BrooklynDslCommon.formatString(Strings.repeat("%s\n", dsls.size()), dsls.toArray()));
        }

        protected abstract Optional<Object> resolveIncludingToscaFunctions(Map<String, IValue> inputParameters, String key, PaaSNodeTemplate paasNodeTemplate, Map<String, PaaSNodeTemplate> builtPaaSNodeTemplates);

    }

    class NodeScriptGenerator extends ScriptGenerator {

        public NodeScriptGenerator(String opKey, String nodeId, Alien4CloudApplication toscaApplication, String computeName, String expandedFolder) {
            super(opKey, nodeId, toscaApplication, computeName, expandedFolder);
        }

        @Override
        Object getScript(ImplementationArtifact artifact, Operation op, @Nullable ManagementContext mgmt) {
            String script = getScript(artifact);
            return buildExportStatements(op, script, mgmt).or(script);
        }

        @Override
        Operation getOperation() {
            return getStandardInterfaceOperationsMap(nodeId, toscaApplication).get(opKey);
        }

        @Override
        protected Optional<Object> resolveIncludingToscaFunctions(Map<String, IValue> inputParameters, String key, PaaSNodeTemplate paasNodeTemplate, Map<String, PaaSNodeTemplate> builtPaaSNodeTemplates) {
            return Alien4CloudFacade.this.resolveKeyIncludingToscaFunctions(inputParameters, key, paasNodeTemplate, builtPaaSNodeTemplates, toscaApplication.getKeywordMap(nodeId));
        }
    }

    class RelationshipScriptGenerator extends ScriptGenerator {

        private ToscaApplication.Relationship relationship;
        private RelationshipTemplate relationshipTemplate;

        public RelationshipScriptGenerator(String opKey, Alien4CloudApplication toscaApplication, ToscaApplication.Relationship relationship, String computeName, String expandedFolder) {
            super(opKey, relationship.getSourceNodeId(), toscaApplication, computeName, expandedFolder);
            this.relationship = relationship;
        }

        @Override
        Object getScript(ImplementationArtifact artifact, Operation op, @Nullable ManagementContext mgmt) {
            Optional<RelationshipTemplate> optionalRelationshipTemplate = Optional.fromNullable(toscaApplication.getNodeTemplate(nodeId).getRelationships().get(relationship.getRelationshipId()));
            if (!optionalRelationshipTemplate.isPresent()) {
                LOG.warn("Unsupported operation implementation for " + op.getDescription() + ": no relationship template");
                return null;
            }
            this.relationshipTemplate = optionalRelationshipTemplate.get();

            String script = getScript(artifact);
            return buildExportStatements(op, script, mgmt).or(script);
        }

        @Override
        Operation getOperation() {
            return getConfigureInterfaceOperationsMap(toscaApplication, relationship).get(opKey);
        }

        @Override
        protected Optional<Object> resolveIncludingToscaFunctions(Map<String, IValue> inputParameters, String key, PaaSNodeTemplate paasNodeTemplate, Map<String, PaaSNodeTemplate> builtPaaSNodeTemplates) {
            PaaSRelationshipTemplate paaSRelationshipTemplate =  paasNodeTemplate.getRelationshipTemplate(relationship.getRelationshipId(), nodeId);
            Map<String, String> keywordMap = toscaApplication.getKeywordMap(toscaApplication.getNodeTemplate(nodeId), relationshipTemplate);
            return Alien4CloudFacade.this.resolveKeyIncludingToscaFunctions(inputParameters, key, paaSRelationshipTemplate, builtPaaSNodeTemplates, keywordMap);
        }
    }
}
