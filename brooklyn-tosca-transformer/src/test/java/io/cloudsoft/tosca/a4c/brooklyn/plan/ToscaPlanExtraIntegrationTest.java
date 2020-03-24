package io.cloudsoft.tosca.a4c.brooklyn.plan;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.sensor.Feed;
import org.apache.brooklyn.core.entity.Dumper;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.mgmt.EntityManagementUtils;
import org.apache.brooklyn.core.mgmt.EntityManagementUtils.CreationResult;
import org.apache.brooklyn.feed.http.HttpFeed;
import org.apache.brooklyn.test.Asserts;
import org.testng.annotations.Test;

import io.cloudsoft.tosca.a4c.Alien4CloudIntegrationTest;

public class ToscaPlanExtraIntegrationTest extends Alien4CloudIntegrationTest {

    @Test
    public void testSensorParamsUsingProperties() throws Exception {
        doNamedSensorTest("properties");
    }
    
    @Test
    public void testSensorParamsFlatNotAllowed() throws Exception {
        try {
            doNamedSensorTest("flat");
            Asserts.shouldHaveFailedPreviously("'name' should not be recognised unless nested");
        } catch (Exception e) {
            // expected
        }
    }
    
    @Test
    public void testSensorParamsUsingBrooklynConfig() throws Exception {
        doNamedSensorTest("brooklyn-config");
    }
    
    protected void doNamedSensorTest(String suffix) throws Exception {
        EntitySpec<? extends Application> appSpec = create("classpath://templates/extra/named-sensors-"+suffix+".yaml");
        assertNotNull(appSpec);
        assertEquals(appSpec.getChildren().size(), 1);
        
        CreationResult<? extends Application, Void> app = EntityManagementUtils.createStarting(mgmt, appSpec);
        app.blockUntilComplete();
        
        Entity server = app.get().getChildren().iterator().next();
        Dumper.dumpInfo(server);
        
        Feed f = ((EntityInternal)server).feeds().getFeeds().iterator().next();
        Asserts.assertInstanceOf(f, HttpFeed.class);
        // very hard to get at the HttpConfig of the poll job buried, so we don't make further assertions
        // but if "name" isn't being passed correctly, creation above will have failed, so this is a reasonable test
    }
    
}
