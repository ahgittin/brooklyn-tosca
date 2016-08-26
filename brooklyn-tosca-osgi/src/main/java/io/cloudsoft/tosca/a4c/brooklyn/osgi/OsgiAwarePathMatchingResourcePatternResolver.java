/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.cloudsoft.tosca.a4c.brooklyn.osgi;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.text.Strings;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.wiring.BundleWiring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public class OsgiAwarePathMatchingResourcePatternResolver extends PathMatchingResourcePatternResolver {

    private static final Logger LOG = LoggerFactory.getLogger(OsgiAwarePathMatchingResourcePatternResolver.class);

    @Override
    public Resource[] getResources(String locationPattern) throws IOException {
        // classpath*:alien4cloud/**/*.class
        if (locationPattern.startsWith("classpath*:")) {
            String pattern = Strings.removeFromStart(locationPattern, "classpath*:");
            int ls = pattern.lastIndexOf('/');
            String path = pattern.substring(0, ls+1);
            String file = pattern.substring(ls+1);
            if (!path.startsWith("/")) path = "/"+ path;
            if (path.endsWith("**/")) path = Strings.removeFromEnd(path, "**/");
            if (Strings.isBlank(file)) file = "*";
            
            ClassLoader loader = OsgiAwarePathMatchingResourcePatternResolver.class.getClassLoader();
            BundleWiring wiring = FrameworkUtil.getBundle(OsgiAwarePathMatchingResourcePatternResolver.class).adapt(BundleWiring.class);
            
            // doesnt' look in bundle classpath :(
            // http://stackoverflow.com/questions/8456617/querying-jar-entries-in-an-eclipse-plugin
//            List<URL> result = wiring.findEntries(path, file, BundleWiring.FINDENTRIES_RECURSE);
            
            // ah, but this does!
            Collection<String> result1 = wiring.listResources(path, file, BundleWiring.LISTRESOURCES_LOCAL | BundleWiring.LISTRESOURCES_RECURSE);
            LOG.debug("Osgi resolver first pass found "+result1.size()+" match(es) for "+locationPattern+" ("+path+" "+file+"): "+result1);
            List<String> result = MutableList.copyOf(result1);
            
//            List<URL> result = new ArrayList<URL>();
//            for (String r: result1) {
//                URL r2 = loader.getResource(r);
//                if (r2==null) {
//                    LOG.warn("Could not load "+r+"; skipping");
//                } else {
//                    result.add( r2 );
//                }
//            }
//            LOG.debug("Osgi resolver found "+result.size()+" match(es) for "+locationPattern+" ("+path+" "+file+"): "+result);
            
//            Resource[] resultA = new Resource[result.size()];
//            for (int i=0; i<result.size(); i++) {
//                resultA[i] = new ClassPathResource(result.get(i), );
//            }
            
            Resource[] resultA = new Resource[result1.size()];
            for (int i=0; i<result1.size(); i++) {
                resultA[i] = new ClassPathResource(result.get(i), loader);
            }

            return resultA;
        } else {
            LOG.debug("Osgi resolver does not know pattern ("+locationPattern+"); passing to super");
            return super.getResources(locationPattern);
        }
    }
}
