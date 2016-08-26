package io.cloudsoft.tosca.a4c.platform;

import alien4cloud.utils.AlienYamlPropertiesFactoryBeanFactory;
import com.google.common.base.Stopwatch;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ImportResource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ResourceLoader;

@ComponentScan
@ImportResource("classpath:/base-context.xml")
public class Alien4CloudSpringContext {

    private static final Logger log = LoggerFactory.getLogger(Alien4CloudSpringContext.class);

    public static ApplicationContext newApplicationContext(ManagementContext mgmt) throws Exception {
        return newApplicationContext(mgmt, null);
    }

    public static ApplicationContext newApplicationContext(ManagementContext mgmt, ResourceLoader resourceLoader) throws Exception {
        log.info("Loading Alien4Cloud platform...");
        // TODO if ES cannot find a config file, it will hang waiting for peers; should warn if does not complete in 1m
        try {
            Stopwatch s = Stopwatch.createStarted();

            AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
            
            if (null != resourceLoader) {
                ctx.setResourceLoader(resourceLoader);
            }

            // messy, but seems we must manually load the properties before loading the beans; otherwise we get e.g.
            // Caused by: java.lang.IllegalArgumentException: Could not resolve placeholder 'directories.alien' in string value "${directories.alien}/plugins"
            final YamlPropertiesFactoryBean yamlPropertiesFactoryBean = AlienBrooklynYamlPropertiesFactoryBeanFactory.get(mgmt, ctx);
            if (yamlPropertiesFactoryBean == null) {
                throw new IllegalStateException("Could not load configuration for A4C. Expected either a value for ConfigKey " +
                        AlienBrooklynYamlPropertiesFactoryBeanFactory.ALIEN_CONFIG_FILE.getName() + " or for a resource named " +
                        AlienYamlPropertiesFactoryBeanFactory.ALIEN_CONFIGURATION_YAML + " to be available.");
            }


            ctx.getEnvironment().getPropertySources().addFirst(new PropertiesPropertySource("user",
                    yamlPropertiesFactoryBean.getObject()));
            ctx.getBeanFactory().registerSingleton("brooklynManagementContext", mgmt);

            ctx.register(Alien4CloudSpringContext.class, Alien4CloudSpringConfig.class);
            ctx.refresh();
            ctx.registerShutdownHook();
            log.info("Finished loading Alien4Cloud platform (" + Duration.of(s) + ")");
            return ctx;

        } catch (Throwable t) {
            log.warn("Errors loading Alien4Cloud platform (rethrowing): " + t, t);
            throw Exceptions.propagate(t);
        }
    }
}
