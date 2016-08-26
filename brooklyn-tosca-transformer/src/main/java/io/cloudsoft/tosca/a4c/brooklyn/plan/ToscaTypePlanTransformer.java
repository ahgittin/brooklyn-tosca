package io.cloudsoft.tosca.a4c.brooklyn.plan;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.cloudsoft.tosca.a4c.brooklyn.ApplicationSpecsBuilder;
import io.cloudsoft.tosca.a4c.brooklyn.ToscaApplication;

import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.internal.AbstractBrooklynObjectSpec;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.api.typereg.RegisteredTypeLoadingContext;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.BrooklynFeatureEnablement;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.mgmt.internal.LocalManagementContext;
import org.apache.brooklyn.core.plan.PlanNotRecognizedException;
import org.apache.brooklyn.core.typereg.AbstractFormatSpecificTypeImplementationPlan;
import org.apache.brooklyn.core.typereg.AbstractTypePlanTransformer;
import org.apache.brooklyn.core.typereg.BasicTypeImplementationPlan;
import org.apache.brooklyn.core.typereg.RegisteredTypes;
import org.apache.brooklyn.entity.stock.BasicApplication;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import com.google.common.annotations.VisibleForTesting;

import io.cloudsoft.tosca.a4c.platform.Alien4CloudSpringContext;
import io.cloudsoft.tosca.a4c.platform.Alien4CloudToscaPlatform;
import io.cloudsoft.tosca.a4c.platform.ToscaPlatform;

import org.springframework.core.io.ResourceLoader;

public class ToscaTypePlanTransformer extends AbstractTypePlanTransformer {

    private static final Logger log = LoggerFactory.getLogger(ToscaTypePlanTransformer.class);
    
    public static final ConfigKey<Alien4CloudToscaPlatform> TOSCA_ALIEN_PLATFORM = ConfigKeys.builder(Alien4CloudToscaPlatform.class)
            .name("tosca.a4c.platform")
            .build();

    @VisibleForTesting
    public static final String FEATURE_TOSCA_ENABLED = BrooklynFeatureEnablement.FEATURE_PROPERTY_PREFIX + ".tosca";
    private static final AtomicBoolean hasLoggedDisabled = new AtomicBoolean(false);

    private static final ConfigKey<String> TOSCA_ID = ConfigKeys.newStringConfigKey("tosca.id");
    private static final ConfigKey<String> TOSCA_DELEGATE_ID = ConfigKeys.newStringConfigKey("tosca.delegate.id");
    private static final ConfigKey<String> TOSCA_DEPLOYMENT_ID = ConfigKeys.newStringConfigKey("tosca.deployment.id");

    private ManagementContext mgmt;
    private ToscaPlatform platform;
    private ResourceLoader resourceLoader;

    private final AtomicBoolean alienInitialised = new AtomicBoolean();

    public static final String FORMAT = "brooklyn-tosca";

    public ToscaTypePlanTransformer() {
        super(FORMAT, "OASIS TOSCA / Brooklyn", "The Apache Brooklyn implementation of the OASIS TOSCA blueprint plan format and extensions");
    }

    @Override
    public void setManagementContext(ManagementContext managementContext) {
        if (!isEnabled()) {
            if (hasLoggedDisabled.compareAndSet(false, true)) {
                log.info("Not loading brooklyn-tosca platform: feature disabled");
            }
            return;
        }
        if (this.mgmt != null && this.mgmt != managementContext) {
            throw new IllegalStateException("Cannot switch mgmt context");
        } else if (this.mgmt == null) {
            this.mgmt = managementContext;
            initialiseAlien();
        }
    }

    private void initialiseAlien() {
        try {
            synchronized (ToscaTypePlanTransformer.class) {
                platform = mgmt.getConfig().getConfig(TOSCA_ALIEN_PLATFORM);
                if (platform == null) {
                    Alien4CloudToscaPlatform.grantAdminAuth();
                    ClassLoader oldCL = null;
                    if (getResourceLoader()!=null) {
                        oldCL = Thread.currentThread().getContextClassLoader(); 
                        Thread.currentThread().setContextClassLoader(getResourceLoader().getClass().getClassLoader());
                    }
                    ApplicationContext applicationContext = Alien4CloudSpringContext.newApplicationContext(mgmt, resourceLoader);
                    platform = applicationContext.getBean(ToscaPlatform.class);
                    ((LocalManagementContext) mgmt).getBrooklynProperties().put(TOSCA_ALIEN_PLATFORM, platform);
                    if (oldCL!=null) {
                        Thread.currentThread().setContextClassLoader(oldCL);
                    }
                }
            }
            alienInitialised.set(true);
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }


    public EntitySpec<? extends Application> createApplicationSpecFromTopologyId(String id) {
        return createApplicationSpec(platform.getToscaApplication(id));
    }

    protected EntitySpec<? extends Application> createApplicationSpec(ToscaApplication toscaApplication) {
        EntitySpec<BasicApplication> rootSpec = EntitySpec.create(BasicApplication.class)
                .displayName(toscaApplication.getName())
                .configure(TOSCA_ID, toscaApplication.getId())
                .configure(TOSCA_DELEGATE_ID, toscaApplication.getDelegateId())
                .configure(TOSCA_DEPLOYMENT_ID, toscaApplication.getDeploymentId());

        ApplicationSpecsBuilder specsBuilder = platform.getBean(ApplicationSpecsBuilder.class);
        Map<String, EntitySpec<?>> specs = specsBuilder.getSpecs(toscaApplication);
        rootSpec.children(specs.values());
        specsBuilder.addPolicies(rootSpec, toscaApplication, specs);

        log.debug("Created entity from TOSCA spec: " + rootSpec);
        return rootSpec;
    }

    @Override
    public AbstractBrooklynObjectSpec<?, ?> createSpec(RegisteredType type, RegisteredTypeLoadingContext context) throws Exception {
        String planYaml = String.valueOf(type.getPlan().getPlanData());
        assertAvailable();
        try {
            Alien4CloudToscaPlatform.grantAdminAuth();
            return createApplicationSpec(platform.parse(planYaml));
        } catch (Exception e) {
            if (e instanceof PlanNotRecognizedException) {
                if (log.isTraceEnabled())
                    log.trace("Failed to create entity from TOSCA spec:\n" + planYaml, e);
            } else {
                if (log.isDebugEnabled())
                    log.debug("Failed to create entity from TOSCA spec:\n" + planYaml, e);
            }
            throw Exceptions.propagate(e);
        }
    }

    private boolean isEnabled() {
        return BrooklynFeatureEnablement.isEnabled(FEATURE_TOSCA_ENABLED);
    }

    /**
     * Throws {@link IllegalStateException} if {@link BrooklynFeatureEnablement#isEnabled(String)}
     * returns false for {@link #FEATURE_TOSCA_ENABLED} or if {@link #alienInitialised} is false.
     */
    private void assertAvailable() {
        if (!BrooklynFeatureEnablement.isEnabled(FEATURE_TOSCA_ENABLED)) {
            throw new PlanNotRecognizedException("Brooklyn TOSCA support is disabled");
        } else if (!alienInitialised.get()) {
            throw new PlanNotRecognizedException("Alien4Cloud platform is uninitialised for " + this);
        }
    }

    @Override
    public double scoreForTypeDefinition(String formatCode, Object catalogData) {
        return 0; // TODO: Not yet implemented
    }

    @Override
    public List<RegisteredType> createFromTypeDefinition(String formatCode, Object catalogData) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected double scoreForNullFormat(Object planData, RegisteredType type, RegisteredTypeLoadingContext context) {
        Maybe<Map<?, ?>> yamlMap = RegisteredTypes.getAsYamlMap(planData);
        if (yamlMap.isAbsent() || !yamlMap.get().containsKey("tosca_definitions_version")) {
            return 0;
        }
        return 1;
    }

    @Override
    protected double scoreForNonmatchingNonnullFormat(String planFormat, Object planData, RegisteredType type, RegisteredTypeLoadingContext context) {
        return planFormat.equals(FORMAT) ? 0.9 : 0;
    }

    @Override
    protected Object createBean(RegisteredType type, RegisteredTypeLoadingContext context) throws Exception {
        return null;
    }

    public static class ToscaTypeImplementationPlan extends AbstractFormatSpecificTypeImplementationPlan<String> {
        public ToscaTypeImplementationPlan(RegisteredType.TypeImplementationPlan otherPlan) {
            super(FORMAT, String.class, otherPlan);
        }
        public ToscaTypeImplementationPlan(String planData) {
            this(new BasicTypeImplementationPlan(FORMAT, planData));
        }
    }


    public ResourceLoader getResourceLoader() {
        return resourceLoader;
    }

    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }
}
