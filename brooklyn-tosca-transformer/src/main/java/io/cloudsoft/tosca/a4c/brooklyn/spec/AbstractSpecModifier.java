package io.cloudsoft.tosca.a4c.brooklyn.spec;

import javax.annotation.Nullable;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.DslUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.reflect.TypeToken;

import io.cloudsoft.tosca.a4c.brooklyn.ToscaApplication;
import io.cloudsoft.tosca.a4c.brooklyn.ToscaFacade;

public abstract class AbstractSpecModifier implements EntitySpecModifier {

    @SuppressWarnings("unused")
    private static final Logger LOG = LoggerFactory.getLogger(AbstractSpecModifier.class);

    protected final ManagementContext mgmt;
    private ToscaFacade<? extends ToscaApplication> alien4CloudFacade;

    protected AbstractSpecModifier(ManagementContext mgmt, ToscaFacade<? extends ToscaApplication> alien4CloudFacade) {
        this.mgmt = mgmt;
        this.alien4CloudFacade = alien4CloudFacade;
    }

    @SuppressWarnings("unchecked")
    protected ToscaFacade<ToscaApplication> getToscaFacade() {
        return (ToscaFacade<ToscaApplication>) alien4CloudFacade;
    }

    protected Optional<Object> resolveBrooklynDslValue(Object unresolvedValue, @SuppressWarnings("rawtypes") Optional<TypeToken> desiredType, @Nullable EntitySpec<?> spec) {
        return resolveBrooklynDslValue(unresolvedValue, desiredType, mgmt, spec);
    }

    public static Optional<Object> resolveBrooklynDslValue(Object originalValue, @SuppressWarnings("rawtypes") Optional<TypeToken> desiredType, @Nullable ManagementContext mgmt, @Nullable EntitySpec<?> spec) {
        return DslUtils.resolveBrooklynDslValue(originalValue, desiredType.orNull(), mgmt, spec);
    }
}
