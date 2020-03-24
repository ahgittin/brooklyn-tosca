package io.cloudsoft.tosca.a4c.brooklyn.plan;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityInitializer;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.policy.PolicySpec;
import org.apache.brooklyn.api.sensor.Enricher;
import org.apache.brooklyn.api.sensor.EnricherSpec;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.BrooklynDslDeferredSupplier;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.methods.BrooklynDslCommon;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Dumper;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityAsserts;
import org.apache.brooklyn.core.location.PortRanges;
import org.apache.brooklyn.core.mgmt.EntityManagementUtils;
import org.apache.brooklyn.core.mgmt.EntityManagementUtils.CreationResult;
import org.apache.brooklyn.core.mgmt.ha.OsgiBundleInstallationResult;
import org.apache.brooklyn.core.mgmt.internal.LocalManagementContext;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.core.sensor.StaticSensor;
import org.apache.brooklyn.core.test.entity.LocalManagementContextForTests;
import org.apache.brooklyn.core.test.policy.TestPolicy;
import org.apache.brooklyn.enricher.stock.Transformer;
import org.apache.brooklyn.entity.database.mysql.MySqlNode;
import org.apache.brooklyn.entity.group.DynamicCluster;
import org.apache.brooklyn.entity.software.base.SameServerEntity;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.software.base.VanillaSoftwareProcess;
import org.apache.brooklyn.entity.stock.BasicApplication;
import org.apache.brooklyn.entity.webapp.tomcat.TomcatServer;
import org.apache.brooklyn.policy.autoscaling.AutoScalerPolicy;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.exceptions.UserFacingException;
import org.apache.brooklyn.util.stream.Streams;
import org.apache.brooklyn.util.text.StringPredicates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.io.Files;

import alien4cloud.component.repository.exception.CSARVersionAlreadyExistsException;
import alien4cloud.tosca.parser.ParsingException;
import alien4cloud.utils.FileUtil;
import io.cloudsoft.tosca.a4c.Alien4CloudIntegrationTest;
import io.cloudsoft.tosca.a4c.brooklyn.ToscaApplication;
import io.cloudsoft.tosca.a4c.brooklyn.util.EntitySpecs;

public class ToscaTypePlanTransformerIntegrationTest extends Alien4CloudIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ToscaTypePlanTransformerIntegrationTest.class);
    
    private static String DATABASE_DEPENDENCY_INJECTION(boolean resolveExternal) {
        return "$brooklyn:formatString(\"jdbc:" +
            "%s%s?user=%s\\\\&password=%s\", entity(\"mysql_server\")" +
            ".attributeWhenReady(\"datastore.url\"), " +
            "\"visitors\", " +
            "\"brooklyn\", " +
            (resolveExternal ? "br00klyn" : "external(\"brooklyn-demo-sample\", \"hidden-brooklyn-password\")")
            + ")";
    }

    @Test
    public void testSimpleHostedTopologyParser() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/simple-web-server.yaml");
        assertNotNull(app);
        assertEquals(app.getChildren().size(), 1);

        EntitySpec<?> server = app.getChildren().get(0);
        assertEquals(server.getConfig().get(SoftwareProcess.CHILDREN_STARTABLE_MODE),
                SoftwareProcess.ChildStartableMode.BACKGROUND_LATE);

        assertEquals(server.getChildren().size(), 1);

        EntitySpec<?> hostedSoftwareComponent = server.getChildren().get(0);

        assertEquals(server.getFlags().get("tosca.node.type"), "tosca.nodes.Compute");
        assertEquals(server.getType(), SameServerEntity.class);
        assertEquals(server.getLocationSpecs().size(), 1);
        assertEquals(server.getLocationSpecs().get(0).getFlags().get("name"), "localhost");

        assertEquals(hostedSoftwareComponent.getFlags().get("tosca.node.type"),
                "tosca.nodes.SoftwareComponent");
        assertEquals(hostedSoftwareComponent.getType().getName(),
                "org.apache.brooklyn.entity.software.base.VanillaSoftwareProcess");

        assertFlagValueContains(hostedSoftwareComponent, VanillaSoftwareProcess.INSTALL_COMMAND.getName(),
                "# install python if not present");
        assertFlagValueContains(hostedSoftwareComponent, VanillaSoftwareProcess.CUSTOMIZE_COMMAND.getName(),
                "# create the web page to serve");
        assertFlagValueContains(hostedSoftwareComponent, VanillaSoftwareProcess.LAUNCH_COMMAND.getName(),
                "# launch in background (ensuring no streams open), and record PID to file");
        assertFlagValueContains(hostedSoftwareComponent, VanillaSoftwareProcess.STOP_COMMAND.getName(),
                "kill -9 `cat ${PID_FILE:-pid.txt}`");
    }
    
    @Test
    public void testSimpleWithToscaParseError() throws Exception {
        try {
            create("classpath://templates/simple-with-error.yaml");
            Asserts.shouldHaveFailedPreviously();
        } catch (UserFacingException e) {
            Asserts.assertThat(e.toString(), StringPredicates.containsAllLiterals("REQUIREMENT_TARGET_NOT_FOUND", "TOSCA"));
        }
    }

    @Test
    public void testSimpleWithPlanTransformError() throws Exception {
        try {
            EntityManagementUtils.createEntitySpecForApplication(mgmt, new ResourceUtils(mgmt).getResourceAsString(
                "classpath://templates/simple-with-error.yaml")); 
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            Asserts.assertThat(e.toString(), StringPredicates.containsAllLiterals("REQUIREMENT_TARGET_NOT_FOUND", "TOSCA"));
        }
    }

    @Test
    public void testSimpleWithPlanTransformErrorInYaml() throws Exception {
        try {
            EntityManagementUtils.createEntitySpecForApplication(mgmt, new ResourceUtils(mgmt).getResourceAsString(
                "classpath://templates/simple-with-yaml-error.yaml")); 
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            log.info("Caught error as expected: "+e);
            Asserts.assertThat(e.toString(), StringPredicates.containsAllLiterals("YAML", "TOSCA"));
            Asserts.assertStringDoesNotContain(e.toString(), "REQUIREMENT_TARGET_NOT_FOUND");
        }
    }

    @Test
    public void testParentChild() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/parent-child.yaml");
        assertNotNull(app);
        assertEquals(app.getChildren().size(), 1);

        EntitySpec<?> p = Iterables.getOnlyElement( app.getChildren() );
        EntitySpec<?> c = Iterables.getOnlyElement( p.getChildren() );
        assertEquals(p.getConfig().get(ConfigKeys.newStringConfigKey("x")), "1");
        assertEquals(c.getConfig().get(ConfigKeys.newStringConfigKey("x")), "2");
    }

    @Test
    public void testDslInChatApplication() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/helloworld-sql.tosca.yaml");
        assertNotNull(app);
        assertEquals(app.getChildren().size(), 2);

        EntitySpec<?> tomcatServer = EntitySpecs
                .findChildEntitySpecByPlanId(app, "tomcat_server");
        assertEquals(tomcatServer.getConfig().get(TomcatServer.ROOT_WAR),
                "http://search.maven.org/remotecontent?filepath=io/brooklyn/example/" +
                        "brooklyn-example-hello-world-sql-webapp/0.6.0/" +
                        "brooklyn-example-hello-world-sql-webapp-0.6.0.war");

        Map<?,?> javaSysProps = (Map<?,?>) tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS);
        assertNotNull(javaSysProps);
        assertEquals(javaSysProps.size(), 1);
        assertTrue(javaSysProps.get("brooklyn.example.db.url") instanceof BrooklynDslDeferredSupplier);
        assertEquals(javaSysProps.get("brooklyn.example.db.url").toString(), DATABASE_DEPENDENCY_INJECTION(false));

        javaSysProps = (Map<?,?>) tomcatServer.getConfig().get(ConfigKeys.newConfigKey(Map.class, TomcatServer.JAVA_SYSPROPS.getName()));
        assertNotNull(javaSysProps);
        assertEquals(javaSysProps.size(), 1);
        assertTrue(javaSysProps.get("brooklyn.example.db.url") instanceof BrooklynDslDeferredSupplier);
        assertEquals(javaSysProps.get("brooklyn.example.db.url").toString(), DATABASE_DEPENDENCY_INJECTION(false));
        
        assertEquals(tomcatServer.getLocationSpecs().size(), 1, "Expected one LocationSpec");
        assertTrue(tomcatServer.getLocationSpecs().get(0) instanceof LocationSpec);
    }
    
    @Test
    public void testDslInChatApplicationYamlBaseType() throws Exception {
        addCatalogItems(ResourceUtils.create(this).getResourceAsString("classpath://templates/tomcat-node/tomcat-node.bom"));
        EntitySpec<? extends Application> app = create("classpath://templates/tomcat-node/helloworld-sql.tosca.yaml");
        assertNotNull(app);
        assertEquals(app.getChildren().size(), 2);

        EntitySpec<?> tomcatServer = EntitySpecs
                .findChildEntitySpecByPlanId(app, "tomcat_server");
        assertEquals(tomcatServer.getConfig().get(ConfigKeys.newStringConfigKey("root.war")),
                "http://search.maven.org/remotecontent?filepath=io/brooklyn/example/" +
                        "brooklyn-example-hello-world-sql-webapp/0.6.0/" +
                        "brooklyn-example-hello-world-sql-webapp-0.6.0.war");

        Map<?,?> catalinaProps = (Map<?,?>) tomcatServer.getConfig().get(ConfigKeys.newConfigKey(Map.class, "catalina.properties"));
        assertNotNull(catalinaProps);
        assertEquals(catalinaProps.size(), 1);
        assertTrue(catalinaProps.get("brooklyn.example.db.url") instanceof BrooklynDslDeferredSupplier,
            "Expected supplier, got "+catalinaProps.get("brooklyn.example.db.url"));
        assertEquals(catalinaProps.get("brooklyn.example.db.url").toString(), DATABASE_DEPENDENCY_INJECTION(false));

        assertEquals(tomcatServer.getLocationSpecs().size(), 1, "Expected one LocationSpec");
        assertTrue(tomcatServer.getLocationSpecs().get(0) instanceof LocationSpec);
    }
    
    @Test
    public void testBaseTypeFromBrooklynViaCsar() throws Exception {
        addCatalogItems(ResourceUtils.create(this).getResourceAsString("classpath://templates/tomcat-node/types-from-tosca-csar.bom"));
        addCatalogItems(ResourceUtils.create(this).getResourceAsString("classpath://templates/tomcat-node/tomcat-node.bom"));
        EntitySpec<? extends Application> app = create("classpath://templates/tomcat-node/tomcat-from-brooklyn.tosca.yaml");
        assertNotNull(app);
        assertEquals(app.getChildren().size(), 1);
        EntitySpec<?> tomcatServer = EntitySpecs
                .findChildEntitySpecByPlanId(app, "tomcat_server");
        assertEquals(tomcatServer.getConfig().get(ConfigKeys.newStringConfigKey("root.war")),
                "http://search.maven.org/remotecontent?filepath=io/brooklyn/example/" +
                        "brooklyn-example-hello-world-sql-webapp/0.6.0/" +
                        "brooklyn-example-hello-world-sql-webapp-0.6.0.war");
    }

    @Test
    public void testBaseTypeFromBrooklynViaYaml() throws Exception {
        addCatalogItems(ResourceUtils.create(this).getResourceAsString("classpath://templates/tomcat-node/types-from-tosca-yaml.bom"));
        addCatalogItems(ResourceUtils.create(this).getResourceAsString("classpath://templates/tomcat-node/tomcat-node.bom"));
        EntitySpec<? extends Application> app = create("classpath://templates/tomcat-node/tomcat-from-brooklyn.tosca.yaml");
        assertNotNull(app);
        assertEquals(app.getChildren().size(), 1);
        EntitySpec<?> tomcatServer = EntitySpecs
                .findChildEntitySpecByPlanId(app, "tomcat_server");
        assertEquals(tomcatServer.getConfig().get(ConfigKeys.newStringConfigKey("root.war")),
                "http://search.maven.org/remotecontent?filepath=io/brooklyn/example/" +
                        "brooklyn-example-hello-world-sql-webapp/0.6.0/" +
                        "brooklyn-example-hello-world-sql-webapp-0.6.0.war");
    }

    @Test
    public void testBaseTypeFromBrooklynViaYamlLinked() throws Exception {
        addCatalogItems(ResourceUtils.create(this).getResourceAsString("classpath://templates/tomcat-node/types-from-tosca-yaml-linked.bom"));
        addCatalogItems(ResourceUtils.create(this).getResourceAsString("classpath://templates/tomcat-node/tomcat-node.bom"));
        EntitySpec<? extends Application> app = create("classpath://templates/tomcat-node/tomcat-from-brooklyn.tosca.yaml");
        assertNotNull(app);
        assertEquals(app.getChildren().size(), 1);
        EntitySpec<?> tomcatServer = EntitySpecs
                .findChildEntitySpecByPlanId(app, "tomcat_server");
        assertEquals(tomcatServer.getConfig().get(ConfigKeys.newStringConfigKey("root.war")),
                "http://search.maven.org/remotecontent?filepath=io/brooklyn/example/" +
                        "brooklyn-example-hello-world-sql-webapp/0.6.0/" +
                        "brooklyn-example-hello-world-sql-webapp-0.6.0.war");
    }

    @Test
    public void testFullJcloudsLocationDescription() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/full-location.jclouds.tosca.yaml");
        assertNotNull(app);
        assertEquals(app.getChildren().size(), 1);

        EntitySpec<?> vanillaEntity = Iterables.getOnlyElement(app.getChildren());
        assertEquals(vanillaEntity.getLocationSpecs().size(), 1);
        LocationSpec<?> locationSpec = Iterables.getOnlyElement(vanillaEntity.getLocationSpecs());
        assertEquals(locationSpec.getFlags().get("provider"), "aws-ec2");
        assertEquals(locationSpec.getFlags().get("region"), "us-west-2");
        assertEquals(locationSpec.getFlags().get("identity"), "user-key-id");
        assertEquals(locationSpec.getFlags().get("credential"), "user-key");
    }

    @Test
    public void testFullByonLocationDescription() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/full-location.byon.tosca.yaml");
        assertNotNull(app);
        assertEquals(app.getChildren().size(), 1);

        EntitySpec<?> vanillaEntity = Iterables.getOnlyElement(app.getChildren());
        assertEquals(vanillaEntity.getLocationSpecs().size(), 1);

        LocationSpec<?> locationSpec = Iterables.getOnlyElement(vanillaEntity.getLocationSpecs());
        Map<String, ?> configByon = locationSpec.getFlags();
        final String configStr = Joiner.on(", ").withKeyValueSeparator("=").join(configByon);
        assertEquals(configByon.get("user"), "brooklyn", "expected user=brooklyn in " + configStr);
        assertEquals(configByon.get("provider"), "byon", "expected provider=byon in " + configStr);

        List<?> machineSpecs = (List<?>) configByon.get("byon.machineSpecs");
        assertNotNull(machineSpecs, "expected byon.machineSpecs != null in " + configStr);
        assertEquals(machineSpecs.size(), 1, "machineSpecs=" + Iterables.toString(machineSpecs));

        LocationSpec<?> spec = (LocationSpec<?>) machineSpecs.get(0);
        assertEquals(spec.getFlags().get("address"), "192.168.0.18", "expected address=192.168.0.18 in location flags " +
                Joiner.on(", ").withKeyValueSeparator("=").join(spec.getFlags()));
    }

    @Test
    public void testRelation() throws ParsingException, CSARVersionAlreadyExistsException, IOException {
        Path outputPath = makeOutputPath("relationship.yaml", "relation", "test.sh", "target.sh");
        ToscaApplication toscaApplication = platform.parse(outputPath);
        EntitySpec<? extends Application> app = transformer.createApplicationSpec(toscaApplication);

        assertNotNull(app);
        assertEquals(app.getChildren().size(), 2);

        EntitySpec<?> tomcatServer = EntitySpecs
                .findChildEntitySpecByPlanId(app, "tomcat_server");

        assertNotNull(tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS));
        Object dbUrl = ((Map<?,?>) tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS)).get("brooklyn.example.db.url");
        assertEquals(((Map<?,?>) tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS)).size(), 1);
        assertEquals(dbUrl.toString(), DATABASE_DEPENDENCY_INJECTION(false));
        assertTrue(dbUrl instanceof BrooklynDslDeferredSupplier, "dbUrl="+dbUrl+", type="+dbUrl.getClass());

        assertFlagValueContains(tomcatServer, VanillaSoftwareProcess.PRE_CUSTOMIZE_COMMAND.getName(),
                "echo It works!");

        EntitySpec<?> mysqlServer = EntitySpecs.findChildEntitySpecByPlanId(app, "mysql_server");

        assertFlagValueContains(mysqlServer, VanillaSoftwareProcess.PRE_CUSTOMIZE_COMMAND.getName(),
                "echo This is the target");
        
        assertEquals(tomcatServer.getConfig().get(TomcatServer.HTTP_PORT.getConfigKey()), PortRanges.fromString("8080+"));
        
        Object httpsPort = tomcatServer.getConfig().get(TomcatServer.HTTPS_PORT.getConfigKey());
        assertTrue(httpsPort instanceof BrooklynDslDeferredSupplier, "httpsPort="+httpsPort+", type="+httpsPort.getClass());
        assertEquals(httpsPort.toString(), "$brooklyn:config(\"http.port\")");
    }


    @Test
    public void testRelationNotOverridePropCollectionConfigKey()
            throws Exception {

        EntitySpec<? extends Application> app = create("classpath://templates/relationship-defined-prop-collection.yaml");
        assertNotNull(app);
        assertEquals(app.getChildren().size(), 2);

        EntitySpec<?> tomcatServer = EntitySpecs
                .findChildEntitySpecByPlanId(app, "tomcat_server");

        assertNotNull(tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS));
        
        
        assertEquals(((Map<?,?>) tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS)).size(), 2);
        assertEquals(((Map<?,?>) tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS)).get("brooklyn.example.db.url").toString(), DATABASE_DEPENDENCY_INJECTION(false));
        assertEquals(((Map<?,?>) tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS)).get("key1").toString(), "value1");
    }

    @Test
    public void testRelationNotOverridePropCollectionFlag()
            throws Exception {

        EntitySpec<? extends Application> app = create("classpath://templates/relationship-defined-prop-collection-flag.yaml");
        assertNotNull(app);
        assertEquals(app.getChildren().size(), 2);

        EntitySpec<?> tomcatServer = EntitySpecs
                .findChildEntitySpecByPlanId(app, "tomcat_server");

        assertNotNull(tomcatServer.getFlags().get("javaSysProps"));
        assertEquals(((Map<?,?>) tomcatServer.getFlags().get("javaSysProps")).size(), 2);
        assertEquals(((Map<?,?>) tomcatServer.getFlags().get("javaSysProps"))
                .get("brooklyn.example.db.url").toString(), DATABASE_DEPENDENCY_INJECTION(false));
        assertEquals(((Map<?,?>) tomcatServer.getFlags().get("javaSysProps"))
                .get("key1").toString(), "value1");
    }

    private Path makeOutputPath(String yamlFile, String scriptsFolder, String... scripts) throws IOException {
        File tempDir = Files.createTempDir();
        tempDir.deleteOnExit();
        File subfolder = new File(tempDir.getAbsolutePath() + "/scripts");
        subfolder.mkdir();

        Streams.copy(new ResourceUtils(this).getResourceFromUrl("classpath://templates/" + yamlFile),
                new FileOutputStream(tempDir.toString() + "/" + yamlFile));
        for (String script : scripts) {
            Streams.copy(new ResourceUtils(this).getResourceFromUrl("classpath://scripts/" + scriptsFolder + "/" + script),
                    new FileOutputStream(subfolder.toString() + "/" + script));
        }

        Path outputPath = java.nio.file.Files.createTempFile("temp", ".zip");
        FileUtil.zip(tempDir.toPath(), outputPath);
        return outputPath;
    }

    @Test
    public void testSomeRelationsForARequirement() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/some-relationships-for-a-requirement.yaml");
        assertNotNull(app);
        assertEquals(app.getChildren().size(), 3);

        EntitySpec<?> tomcatServer = EntitySpecs
                .findChildEntitySpecByPlanId(app, "tomcat_server");

        assertNotNull(tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS));
        assertEquals(((Map<?,?>) tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS)).size(), 2);
        assertEquals(((Map<?,?>) tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS)).get("dbConnection1").toString(), "connection1");
        assertEquals(((Map<?,?>) tomcatServer.getConfig().get(TomcatServer.JAVA_SYSPROPS)).get("dbConnection2").toString(), "connection2");
    }

    @Test
    public void testAddingBrooklynPolicyToEntitySpec() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/autoscaling.policies.tosca.yaml");
        assertNotNull(app);
        EntitySpec<?> cluster = EntitySpecs
                .findChildEntitySpecByPlanId(app, "cluster");

        assertEquals(cluster.getPolicySpecs().size(), 1);
        assertTrue(cluster.getPolicySpecs().get(0).getType().equals(AutoScalerPolicy.class));

        PolicySpec<?> autoScalerPolicy = cluster.getPolicySpecs().get(0);
        assertNotNull(autoScalerPolicy.getFlags());

        Map<String, ?> autoScalerPolicyFlags = autoScalerPolicy.getFlags();
        assertEquals(autoScalerPolicyFlags.size(), 5);
        assertEquals(autoScalerPolicyFlags.get("metricLowerBound"), "10");
        assertEquals(autoScalerPolicyFlags.get("metricUpperBound"), "100");
        assertEquals(autoScalerPolicyFlags.get("minPoolSize"), "1");
        assertEquals(autoScalerPolicyFlags.get("maxPoolSize"), "5");
        assertEquals(autoScalerPolicyFlags.get("metric"),
            BrooklynDslCommon.sensor("org.apache.brooklyn.entity.webapp.DynamicWebAppCluster", "webapp.reqs.perSec.windowed.perNode")
            // DSL is evaluated:
//            "$brooklyn:sensor(" +
//                "\"org.apache.brooklyn.entity.webapp.DynamicWebAppCluster\"," +
//                " \"webapp.reqs.perSec.windowed.perNode\")"
                );
    }

    @Test
    public void testClusterInstantiated() throws Exception {
        EntitySpec<? extends Application> appSpec = create("classpath://templates/cluster.instantiated.tosca.yaml");
        CreationResult<? extends Application, Void> appCreation = EntityManagementUtils.createStarting(mgmt, appSpec);
        Application app = appCreation.blockUntilComplete().get();
        Dumper.dumpInfo(app);
        Entity cluster = Iterables.getOnlyElement( app.getChildren() );
        EntityAsserts.assertAttributeEquals(cluster, Attributes.SERVICE_UP, true);
        Assert.assertEquals(((DynamicCluster)cluster).getMembers().size(), 3);
    }

    @Test(enabled=false)   // TODO should work once d8f73f3a503d559bb08716c89f61413550c3a608 is merged to brooklyn-server, re-enable then
    public void testClusterInstantiatedFromToscaEntitySpec() throws Exception {
        EntitySpec<? extends Application> appSpec = create("classpath://templates/cluster.instantiated.tosca.entity-spec-tosca.yaml");
        CreationResult<? extends Application, Void> appCreation = EntityManagementUtils.createStarting(mgmt, appSpec);
        Application app = appCreation.blockUntilComplete().get();
        Dumper.dumpInfo(app);
        Entity cluster = Iterables.getOnlyElement( app.getChildren() );
        EntityAsserts.assertAttributeEquals(cluster, Attributes.SERVICE_UP, true);
        Assert.assertEquals(((DynamicCluster)cluster).getMembers().size(), 3);
    }

    @Test
    public void testAddingBrooklynPolicyToApplicationSpec() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/simple.application-policies.tosca.yaml");
        assertNotNull(app);

        PolicySpec<?> testPolicy = Iterables.getOnlyElement(app.getPolicySpecs());
        assertTrue(testPolicy.getType().equals(TestPolicy.class));

        Map<String, ?> testPolicyFlags = testPolicy.getFlags();
        assertNotNull(testPolicyFlags);
        assertEquals(testPolicyFlags.size(), 4);
        assertEquals(testPolicyFlags.get("policyLiteralValue1"), "Hello");
        assertEquals(testPolicyFlags.get("policyLiteralValue2"), "World");
        assertEquals(testPolicyFlags.get("test.confName"), "Name from YAML");
        Object confFromFunction = testPolicyFlags.get("test.confFromFunction");
        assertEquals(confFromFunction, "$brooklyn: is a fun place");
    }

    @Test
    public void testAddingEnrichersAsPoliciesAndStarting() throws Exception {
        EntitySpec<? extends Application> appSpec = create("classpath://templates/simple.application-enrichers.tosca.yaml");
        assertNotNull(appSpec);

        assertEquals(appSpec.getEnricherSpecs().size(), 1);
        assertEquals(Transformer.class, appSpec.getEnricherSpecs().get(0).getType());

        EnricherSpec<?> testEnricher = Iterables.getOnlyElement(appSpec.getEnricherSpecs());
        assertTrue(testEnricher.getType().equals(Transformer.class));
        Map<String, ?> testEnricherFlags = testEnricher.getFlags();
        assertNotNull(testEnricherFlags);
        assertNotNull(testEnricherFlags.get("enricher.targetValue"));
        Assert.assertTrue(testEnricherFlags.get("enricher.targetValue") instanceof Supplier, "actually: "+testEnricherFlags+" / "+testEnricherFlags.get("enricher.targetValue").getClass());

        CreationResult<? extends Application, Void> appCreation = EntityManagementUtils.createStarting(mgmt, appSpec);
        Application app = appCreation.blockUntilComplete().get();
        Enricher transformerEnricher = app.getChildren().iterator().next().enrichers().asList().stream()
            .filter(e -> e instanceof Transformer).findFirst().get();
        Dumper.dumpInfo(transformerEnricher);
        assertEquals(Transformer.class, transformerEnricher.getClass());
        assertEquals("org.apache.brooklyn.enricher.stock.Transformer", transformerEnricher.getDisplayName());
    }

    @Test
    public void testAddingBrooklynInitializerToApplicationSpec() throws Exception {
        EntitySpec<? extends Application> appSpec = create("classpath://templates/simple.entity-initializers.tosca.yaml");
        assertNotNull(appSpec);

        EntityInitializer init = Iterables.getOnlyElement(appSpec.getInitializers());
        assertTrue(init instanceof StaticSensor, "Wrong type: "+init);

        CreationResult<? extends Application, Void> appCreation = EntityManagementUtils.createStarting(mgmt, appSpec);
        Application app = appCreation.blockUntilComplete().get();
        Dumper.dumpInfo(app);
        EntityAsserts.assertAttributeEqualsEventually(app, Sensors.newStringSensor("foo"), "bar");
        EntityAsserts.assertAttributeEqualsEventually(Iterables.getOnlyElement(app.getChildren()), Sensors.newStringSensor("foo"), "baz");
    }

    @Test
    public void testMysqlTopology() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/mysql-topology.tosca.yaml");

        // Check the basic structure
        assertNotNull(app, "spec");
        assertEquals(app.getType(), BasicApplication.class);

        assertEquals(app.getChildren().size(), 1, "Expected exactly one child of root application");
        EntitySpec<?> compute = Iterators.getOnlyElement(app.getChildren().iterator());
        assertEquals(compute.getType(), SameServerEntity.class);

        assertEquals(compute.getChildren().size(), 1, "Expected exactly one child of root application");
        EntitySpec<?> mysql = Iterators.getOnlyElement(compute.getChildren().iterator());
        assertEquals(mysql.getType(), VanillaSoftwareProcess.class);

        // Check the config has been set
        assertEquals(mysql.getConfig().get(ConfigKeys.newStringConfigKey("port")), "3306");
        assertEquals(mysql.getConfig().get(ConfigKeys.newStringConfigKey("db_user")), "martin");

        // Check that the inputs have been set as exports on the scripts
        assertFlagValueContains(mysql, VanillaSoftwareProcess.LAUNCH_COMMAND.getName(), "export PORT=\"3306\"");
        assertFlagValueContains(mysql, VanillaSoftwareProcess.LAUNCH_COMMAND.getName(), "export DB_USER=\"martin\"");
        assertFlagValueContains(mysql, VanillaSoftwareProcess.LAUNCH_COMMAND.getName(), "export DB_NAME=\"wordpress\"");
    }

    @Test
    public void testMongoTopology() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/mongo-topology.tosca.yaml");

        // Check the basic structure
        assertNotNull(app, "spec");
        assertEquals(app.getType(), BasicApplication.class);

        assertEquals(app.getChildren().size(), 1, "Expected exactly one child of root application");
        EntitySpec<?> compute = Iterators.getOnlyElement(app.getChildren().iterator());
        assertEquals(compute.getType(), SameServerEntity.class);

        assertEquals(compute.getChildren().size(), 1, "Expected exactly one child of compute entity");
        EntitySpec<?> mongo = Iterators.getOnlyElement(compute.getChildren().iterator());
        assertEquals(mongo.getType(), VanillaSoftwareProcess.class);

        // Check that the inputs have been set as exports on the scripts
        String customize = mongo.getFlags().get(VanillaSoftwareProcess.CUSTOMIZE_COMMAND.getName()).toString();
        assertConfigValueContains(customize, "DB_IP");
        assertConfigValueContains(customize, "entity(\"Compute\").attributeWhenReady(\"ip_address\")");
    }

    // the artifact is copied to the target machine and available as an environment variable in Brooklyn SoftwareProces scripts
    // and by extension within TOSCA SoftwareComponent nodes.  there is no extra config key set because there is no specified
    // mechanism for artifacts to be made available in other places.  there is also no support for the get_artifact function,
    // though LOCAL_FILE could easily be made to work. other paths would be harder to support as the system needs to register
    // where those should be written.
    @Test(enabled = false)
    public void testDeploymentArtifacts() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/deployment-artifact.tosca.yaml");

        assertNotNull(app);
        assertEquals(app.getChildren().size(), 1);

        EntitySpec<?> tomcatServer = EntitySpecs.findChildEntitySpecByPlanId(app, "tomcat_server");
        assertEquals(tomcatServer.getConfig().get(TomcatServer.ROOT_WAR),
                "http://search.maven.org/remotecontent?filepath=io/brooklyn/example/" +
                        "brooklyn-example-hello-world-sql-webapp/0.6.0/" +
                        "brooklyn-example-hello-world-sql-webapp-0.6.0.war");
    }

    // TODO Do not need to use expensive mysql-topology blueprint to test overwriting interfaces.
    @Test
    public void testOverwriteInterfaceOnMysqlTopology() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/mysql-topology-overwritten-interface.tosca.yaml");
        // Check the basic structure
        assertNotNull(app, "spec");
        assertEquals(app.getType(), BasicApplication.class);

        assertEquals(app.getChildren().size(), 1, "Expected exactly one child of root application");
        EntitySpec<?> compute = Iterators.getOnlyElement(app.getChildren().iterator());
        assertEquals(compute.getType(), SameServerEntity.class);

        assertEquals(compute.getChildren().size(), 1, "Expected exactly one child of root application");
        EntitySpec<?> mysql = Iterators.getOnlyElement(compute.getChildren().iterator());
        assertEquals(mysql.getType(), VanillaSoftwareProcess.class);

        // Check that the inputs have been set as exports on the scripts
        assertFlagValueContains(mysql, VanillaSoftwareProcess.LAUNCH_COMMAND.getName(), "#OVERWRITTEN VALUE");
    }
    
    @Test  // fixed with f61c53254a1b088e129242743881ec62d9470cb8 in A4C (cloudsoft repo) and locally
    public void testOverwriteInterfaceOnCustom1Topology() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/custom-overwritten-interface.tosca.yaml");
        // Check the basic structure
        assertNotNull(app, "spec");
        assertEquals(app.getType(), BasicApplication.class);

        assertEquals(app.getChildren().size(), 1, "Expected exactly one child of root application");
        EntitySpec<?> compute = Iterators.getOnlyElement(app.getChildren().iterator());
        assertEquals(compute.getType(), SameServerEntity.class);

        assertEquals(compute.getChildren().size(), 1, "Expected exactly one grandchild of root application");
        EntitySpec<?> custom1 = Iterators.getOnlyElement(compute.getChildren().iterator());
        assertEquals(custom1.getType(), VanillaSoftwareProcess.class);

        log.info("SoftwareProcess:\n  flags="+custom1.getFlags() + "\n  config="+custom1.getConfig());
        // Check that the inputs have been set as exports on the scripts, and the script is still set
        assertFlagValueContains(custom1, VanillaSoftwareProcess.CUSTOMIZE_COMMAND.getName(), "export arg1");
        assertFlagValueContains(custom1, VanillaSoftwareProcess.CUSTOMIZE_COMMAND.getName(), "export arg2");
        assertFlagValueContains(custom1, VanillaSoftwareProcess.CUSTOMIZE_COMMAND.getName(), "echo configure arg1"); // in configure.sh
    }

    @Test
    public void testPropertiesIncludingList() throws Exception {
        EntitySpec<? extends Application> appSpec = create("classpath://templates/properties-tosca-list.yaml");
        // Check the basic structure
        assertNotNull(appSpec, "spec");
        assertEquals(appSpec.getType(), BasicApplication.class);

        assertEquals(appSpec.getChildren().size(), 1, "Expected exactly one child of root application");
        EntitySpec<?> compute = Iterators.getOnlyElement(appSpec.getChildren().iterator());
        assertEquals(compute.getType(), SameServerEntity.class);

        assertEquals(compute.getChildren().size(), 1, "Expected exactly one grandchild of root application");
        EntitySpec<?> custom1 = Iterators.getOnlyElement(compute.getChildren().iterator());
        assertEquals(custom1.getType(), VanillaSoftwareProcess.class);

        // Check that the inputs have been set as exports on the scripts
        List<String> expected = Arrays.asList("arg1=\"foo\"", "arg2=\"bar\"", "arg3=\"boo\"");
        expected.forEach(s -> assertFlagValueContains(custom1, VanillaSoftwareProcess.CUSTOMIZE_COMMAND.getName(), s));
        
        // and deploy and ensure we get the attribute
        
        Application appInst = this.mgmt.getEntityManager().createEntity(appSpec);
        Entity custom1I = Iterables.getOnlyElement( Iterables.getOnlyElement( appInst.getChildren() ).getChildren() );
        Dumper.dumpInfo(custom1I);
        String customCmd = custom1I.config().get(VanillaSoftwareProcess.CUSTOMIZE_COMMAND);
        expected.forEach(s -> Asserts.assertStringContains(customCmd, s));
        Asserts.assertStringDoesNotContain(customCmd, "attributeWhenReady", "bip", "baz");
    }
    
    // quite restrictive what is supported in A4C:
    // attribute can only be set on node type, not in node template.
    // it can only define a static _default_ or a subset of functions, operation output or concat.
    // access to other attributes or properties has to be nested.
    // this test demonstrates one path which works.
    @Test
    public void testChainAttributePropertyOnCustom1Topology() throws Exception {
        EntitySpec<? extends Application> appSpec = create("classpath://templates/chained-attribute-property.tosca.yaml");
        // Check the basic structure
        assertNotNull(appSpec, "spec");
        assertEquals(appSpec.getType(), BasicApplication.class);

        assertEquals(appSpec.getChildren().size(), 1, "Expected exactly one child of root application");
        EntitySpec<?> compute = Iterators.getOnlyElement(appSpec.getChildren().iterator());
        assertEquals(compute.getType(), SameServerEntity.class);

        assertEquals(compute.getChildren().size(), 1, "Expected exactly one grandchild of root application");
        EntitySpec<?> custom1 = Iterators.getOnlyElement(compute.getChildren().iterator());
        assertEquals(custom1.getType(), VanillaSoftwareProcess.class);

        // Check that the inputs have been set as exports on the scripts
        assertFlagValueContains(custom1, VanillaSoftwareProcess.CUSTOMIZE_COMMAND.getName(), "arg1");
        assertFlagValueContains(custom1, VanillaSoftwareProcess.CUSTOMIZE_COMMAND.getName(), "attributeWhenReady");
        assertFlagValueContains(custom1, VanillaSoftwareProcess.CUSTOMIZE_COMMAND.getName(), "a1");
        
        // and deploy and ensure we get the attribute
        
        Application appInst = this.mgmt.getEntityManager().createEntity(appSpec);
        Entity custom1I = Iterables.getOnlyElement( Iterables.getOnlyElement( appInst.getChildren() ).getChildren() );
        Dumper.dumpInfo(custom1I);
        EntityAsserts.assertAttributeEqualsEventually(custom1I, Sensors.newStringSensor("a1"), "bar" );
        String customCmd = custom1I.config().get(VanillaSoftwareProcess.CUSTOMIZE_COMMAND);
        Asserts.assertStringContains(customCmd, "arg1", "bar");
        Asserts.assertStringDoesNotContain(customCmd, "attributeWhenReady", "a1");
    }
    
    @Test
    public void testChainPropertyPropertyOnCustom1TopologyNiceError() throws Exception {
        try {
            create("classpath://templates/chained-property-property.tosca.invalid.yaml");
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            Asserts.assertStringContainsIgnoreCase(e.toString(), "property", "attribute");
        }
    }
    
    @Test  // works since ac59c0c64ea5784adcdf11a7baad6b1a6a30ca34 on alien4cloud (cloudsoft repo)
    public void testArtifactsOnCustomTopology() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/artifacts.tosca.yaml");
        // Check the basic structure
        assertNotNull(app, "spec");
        assertEquals(app.getType(), BasicApplication.class);

        assertEquals(app.getChildren().size(), 1, "Expected exactly one child of root application");
        EntitySpec<?> compute = Iterators.getOnlyElement(app.getChildren().iterator());
        assertEquals(compute.getType(), SameServerEntity.class);

        assertEquals(compute.getChildren().size(), 1, "Expected exactly one grandchild of root application");
        EntitySpec<?> custom1 = Iterators.getOnlyElement(compute.getChildren().iterator());
        assertEquals(custom1.getType(), VanillaSoftwareProcess.class);

        log.info("flags: "+custom1.getConfig());
        
        Map<String, String> files = ConfigBag.newInstance(custom1.getConfig()).get(VanillaSoftwareProcess.PRE_INSTALL_FILES);
        Asserts.assertThat(files, m -> m.containsKey("classpath://templates/family-chat.war"));
        Asserts.assertStringContains(files.get("classpath://templates/family-chat.war").toString(), "/my_art");
        
        Object my_art = ConfigBag.newInstance(custom1.getConfig()).get(VanillaSoftwareProcess.SHELL_ENVIRONMENT.subKey("my_art"));
        Asserts.assertNotNull(my_art);
        Asserts.assertStringContains(my_art.toString(), "attributeWhenReady", "install.dir", "my_art");
    }
    
    @Test
    public void testArtifactsOnCustomTopologyToscaSyntax() throws Exception {
        try {
            create("classpath://templates/artifacts.csar.tosca.yaml");
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            Asserts.expectedFailureContains(e, "Cannot find artifact my_art", "(/templates/family-chat.war)");
        }
    }
    
    @Test
    public void testEntitiesOnSameNodeBecomeSameServerEntities() throws Exception {
        EntitySpec<? extends Application> spec = create("classpath://templates/tomcat-mysql-on-one-compute.yaml");

        assertNotNull(spec);
        Application app = this.mgmt.getEntityManager().createEntity(spec);

        assertEquals(app.getChildren().size(), 1);
        Entity appChild = Iterables.getOnlyElement(app.getChildren());
        assertTrue(appChild instanceof SameServerEntity, "Expected " + SameServerEntity.class.getName() + ", got " + appChild);

        assertEquals(appChild.getChildren().size(), 2);
        assertEquals(Iterables.size(Entities.descendantsAndSelf(appChild, MySqlNode.class)), 1,
                "expected " + MySqlNode.class.getName() + " in " + appChild.getChildren());
        assertEquals(Iterables.size(Entities.descendantsAndSelf(appChild, TomcatServer.class)), 1,
                "expected " + TomcatServer.class.getName() + " in " + appChild.getChildren());
    }

    @Test
    public void testConcatFunctionInTopology() throws Exception {
        EntitySpec<? extends Application> spec = create("classpath://templates/concat-function.yaml");

        assertNotNull(spec);
        Application app = this.mgmt.getEntityManager().createEntity(spec);

        assertEquals(app.getChildren().size(), 1);
        Entity entity = Iterators.getOnlyElement(app.getChildren().iterator());
        EntityAsserts.assertAttributeEqualsEventually(entity, Sensors.newStringSensor("my_message"), "Message: It Works!");
    }

    @Test
    // TODO old comment that this fails on Linux, due to unknown problem creating topology -- but that might be fixed
    // TODO properties should be republished as attributes
    public void testConcatFunctionWithGetAttributeInTopology() throws Exception {
        EntitySpec<? extends Application> spec = create("classpath://templates/concat-with-get-attribute.tosca.yaml");

        assertNotNull(spec);
        Application app = this.mgmt.getEntityManager().createEntity(spec);
        assertEquals(app.getChildren().size(), 1);
        Entity entity = Iterators.getOnlyElement(app.getChildren().iterator());
        assertEquals(entity.getChildren().size(), 1);
        Entity test = Iterators.getOnlyElement(entity.getChildren().iterator());
        EntityAsserts.assertAttributeEqualsEventually(test, Sensors.newStringSensor("my_message"), "Message: my attribute");
    }

    @Test
    public void testGetAttributeFunctionInTopology() throws Exception {
        EntitySpec<? extends Application> spec = create("classpath://templates/get_attribute-function.yaml");
        assertNotNull(spec);

        Application app = this.mgmt.getEntityManager().createEntity(spec);
        assertEquals(app.getChildren().size(), 1);
        Entity entity = Iterators.getOnlyElement(app.getChildren().iterator());
        final String expected = "Message: Hello";
        EntityAsserts.assertAttributeEqualsEventually(entity, Sensors.newStringSensor("my_message"), expected);
        EntityAsserts.assertPredicateEventuallyTrue(entity, new Predicate<Entity>() {
            @Override
            public boolean apply(@Nullable Entity entity) {
                return entity.config().get(ConfigKeys.newStringConfigKey("another")).equals(expected);
            }
        });
    }

    @Test
    public void testResolvesKeywordInFunction() throws Exception {
        EntitySpec<? extends Application> spec = create("classpath://templates/resolve-keyword-function.yaml");
        assertNotNull(spec);

        Application app = this.mgmt.getEntityManager().createEntity(spec);
        assertEquals(app.getChildren().size(), 1);
        Entity entity = Iterators.getOnlyElement(app.getChildren().iterator());
        final String expected = "Message: Hello";
        EntityAsserts.assertAttributeEqualsEventually(entity, Sensors.newStringSensor("my_message"), expected);
        EntityAsserts.assertPredicateEventuallyTrue(entity, new Predicate<Entity>() {
            @Override
            public boolean apply(@Nullable Entity entity) {
                return entity.config().get(ConfigKeys.newStringConfigKey("another")).equals(expected);
            }
        });
    }

    private void assertFlagValueContains(EntitySpec<?> entity, String key, String needle) {
        String haystack = String.valueOf(entity.getFlags().get(key));
        assertConfigValueContains(haystack, needle);
    }

    private void assertConfigValueContains(String haystack, String needle) {
        if (needle == null || haystack == null) {
            throw new AssertionError("Expected non-null values: needle=" + needle + ", haystack=" + haystack);
        } else if (!haystack.contains(needle)) {
            throw new AssertionError("Expected to find '" + needle + "' in " + haystack);
        }
    }

    @Test
    public void testTransformationFromComputeWithTwoChildrenToSameServer() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/compute-with-two-hosted-children.yaml");
        assertNotNull(app);
        assertEquals(app.getChildren().size(), 1);
        EntitySpec<?> compute = Iterators.getOnlyElement(app.getChildren().iterator());
        assertNotNull(compute);
        assertEquals(compute.getType(), SameServerEntity.class);
    }

    @Test
    public void testChainedRelations() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/chained-relations-frontend-backend-db.yml");
        assertNotNull(app);

        assertEquals(app.getChildren().size(), 3);

        EntitySpec<?> frontend = EntitySpecs
                .findChildEntitySpecByPlanId(app, "frontend");
        EntitySpec<?> backend = EntitySpecs
                .findChildEntitySpecByPlanId(app, "backend");

        assertNotNull(frontend.getConfig().get(TomcatServer.JAVA_SYSPROPS));
        assertEquals(((Map<?,?>) frontend.getConfig().get(TomcatServer.JAVA_SYSPROPS)).size(), 1);
        assertEquals(((Map<?,?>) frontend.getConfig().get(TomcatServer.JAVA_SYSPROPS))
                .get("brooklyn.example.backend.endpoint").toString(), "$brooklyn:entity(\"backend\").attributeWhenReady(\"webapp.url\")");

        assertNotNull(backend.getConfig().get(TomcatServer.JAVA_SYSPROPS));
        assertEquals(((Map<?,?>) backend.getConfig().get(TomcatServer.JAVA_SYSPROPS)).size(), 1);
        assertEquals(((Map<?,?>) backend.getConfig().get(TomcatServer.JAVA_SYSPROPS))
                .get("brooklyn.example.db.url").toString(), DATABASE_DEPENDENCY_INJECTION(false));
    }
    
    @Test
    public void testCsarLink() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/csar-link-external-url.yaml");
        assertNotNull(app);
        assertEquals(app.getChildren().size(), 1);
    }

    @Test
    public void testCsarLinkWithEmbeddedResourcesUsingClasspathUrl() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/csar-link-classpath-url.yaml");
        assertNotNull(app);
        assertEquals(app.getChildren().size(), 1);
    }
    
    @Test
    public void testCsarLinkWithEmbeddedResourcesAsPaths() throws Exception {
        EntitySpec<? extends Application> app = create("classpath://templates/csar-link-path.yaml");
        assertNotNull(app);
        assertEquals(app.getChildren().size(), 1);
    }
    
    protected LocalManagementContext newOsgiMgmt() {
        LocalManagementContext osgiMgmt = LocalManagementContextForTests.builder(true)
                .enableOsgiReusable()
                .build();
        ((ManagementContextInternal) osgiMgmt).getBrooklynProperties().put(ToscaTypePlanTransformer.TOSCA_ALIEN_PLATFORM, platform);
        return osgiMgmt;
    }
    
    @Test
    public void testCsarBomBundleSameZip() throws Exception {
        LocalManagementContext osgiMgmt = newOsgiMgmt();
        OsgiBundleInstallationResult br = ((ManagementContextInternal)osgiMgmt).getOsgiManager().get().install(
            ResourceUtils.create(this).getResourceFromUrl("classpath://templates/csar-bom-bundle-same-zip.zip")).get();
        Assert.assertEquals(br.getBundle().getSymbolicName(), "csar-bom-bundle-same-zip");
        br.getTypesInstalled().stream().anyMatch(t -> t.getId().equals("csar-bom-bundle-same-zip:1.0.0-SNAPSHOT"));
        
        RegisteredType registeredType = osgiMgmt.getTypeRegistry().get("csar-bom-bundle-same-zip");

        ToscaTypePlanTransformer osgiTransformer = new ToscaTypePlanTransformer();
        osgiTransformer.setManagementContext(osgiMgmt);

        @SuppressWarnings("unchecked")
        EntitySpec<? extends Application> app = (EntitySpec<? extends Application>) osgiTransformer.createSpec(registeredType, null);
        
        EntitySpec<?> server = Iterables.getOnlyElement(app.getChildren());
        Assert.assertEquals(server.getDisplayName(), "a_server");
        EntitySpec<?> software = Iterables.getOnlyElement(server.getChildren());
        Object installCommand = software.getFlags().get(VanillaSoftwareProcess.INSTALL_COMMAND.getName());
        Asserts.assertStringContains(""+installCommand, "python", "apt");
        
        Entities.destroyAll(osgiMgmt);
    }
    
    @Test
    public void testCsarWithError() throws Exception {
        LocalManagementContext osgiMgmt = newOsgiMgmt();
        OsgiBundleInstallationResult br = ((ManagementContextInternal)osgiMgmt).getOsgiManager().get().install(
            ResourceUtils.create(this).getResourceFromUrl("classpath://templates/csar-error.zip")).get();
        Assert.assertEquals(br.getBundle().getSymbolicName(), "csar-with-error");
        br.getTypesInstalled().stream().anyMatch(t -> t.getId().equals("csar-with-error:1.0.0-SNAPSHOT"));
        
        RegisteredType registeredType = osgiMgmt.getTypeRegistry().get("csar-with-error");

        ToscaTypePlanTransformer osgiTransformer = new ToscaTypePlanTransformer();
        osgiTransformer.setManagementContext(osgiMgmt);

        try {
            osgiTransformer.createSpec(registeredType, null);
            Asserts.shouldHaveFailedPreviously();
        } catch (UserFacingException e) {
            Asserts.assertThat(e.toString(), StringPredicates.containsAllLiterals("REQUIREMENT_TARGET_NOT_FOUND", "TOSCA"));
        }
        
        Entities.destroyAll(osgiMgmt);
    }

}
