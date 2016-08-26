package io.cloudsoft.tosca.a4c.brooklyn;

import java.util.List;

import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.yaml.Yamls;

import com.google.common.collect.ImmutableList;

public class ConfigLoader {

    private static final String CONFIG_URL = "classpath://brooklyn/brooklyn-tosca-config.yaml";

    private ConfigLoader() {}

    @SuppressWarnings("unchecked")
    public static Iterable<String> getDefaultTypes() {
        String input = new ResourceUtils(ConfigLoader.class.getClassLoader()).getResourceAsString(CONFIG_URL);
        return (List) Yamls.getAt(input, ImmutableList.of("defaultTypes"));
    }

}
