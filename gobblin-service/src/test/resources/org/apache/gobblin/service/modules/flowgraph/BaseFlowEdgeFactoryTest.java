/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.service.modules.flowgraph;

import java.net.URI;
import java.util.Properties;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.service.ServiceConfigKeys;
import org.apache.gobblin.service.modules.template_catalog.FSFlowCatalog;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BaseFlowEdgeFactoryTest {
  @Test
  public void testCreateFlowEdge() throws Exception {
    Properties properties = new Properties();
    properties.put(FlowGraphConfigurationKeys.FLOW_EDGE_END_POINTS_KEY, "node1,node2");
    properties.put(FlowGraphConfigurationKeys.FLOW_EDGE_TEMPLATE_URI_KEY, "FS:///test-template/flow.conf");
    properties.put(FlowGraphConfigurationKeys.FLOW_EDGE_SPEC_EXECUTORS_KEY+".0."+FlowGraphConfigurationKeys.FLOW_EDGE_SPEC_EXECUTOR_CLASS_KEY,"org.apache.gobblin.runtime.spec_executorInstance.InMemorySpecExecutor");
    properties.put(FlowGraphConfigurationKeys.FLOW_EDGE_SPEC_EXECUTORS_KEY+".0.specStore.fs.dir", "/tmp1");
    properties.put(FlowGraphConfigurationKeys.FLOW_EDGE_SPEC_EXECUTORS_KEY+".0.specExecInstance.capabilities", "s1:d1");
    properties.put(FlowGraphConfigurationKeys.FLOW_EDGE_SPEC_EXECUTORS_KEY+".1."+FlowGraphConfigurationKeys.FLOW_EDGE_SPEC_EXECUTOR_CLASS_KEY,"org.apache.gobblin.runtime.spec_executorInstance.InMemorySpecExecutor");
    properties.put(FlowGraphConfigurationKeys.FLOW_EDGE_SPEC_EXECUTORS_KEY+".1.specStore.fs.dir", "/tmp2");
    properties.put(FlowGraphConfigurationKeys.FLOW_EDGE_SPEC_EXECUTORS_KEY+".1.specExecInstance.capabilities", "s2:d2");

    FlowEdgeFactory flowEdgeFactory = new BaseFlowEdge.Factory();

    Properties props = new Properties();
    URI flowTemplateCatalogUri = this.getClass().getClassLoader().getResource("template_catalog").toURI();
    props.put(ServiceConfigKeys.TEMPLATE_CATALOGS_FULLY_QUALIFIED_PATH_KEY, flowTemplateCatalogUri.toString());
    Config config = ConfigFactory.parseProperties(props);
    Config templateCatalogCfg = config
        .withValue(ConfigurationKeys.JOB_CONFIG_FILE_GENERAL_PATH_KEY,
            config.getValue(ServiceConfigKeys.TEMPLATE_CATALOGS_FULLY_QUALIFIED_PATH_KEY));
    FSFlowCatalog catalog = new FSFlowCatalog(templateCatalogCfg);

    FlowEdge flowEdge = flowEdgeFactory.createFlowEdge(properties, catalog);
    Assert.assertEquals(flowEdge.getEndPoints().get(0), "node1");
    Assert.assertEquals(flowEdge.getEndPoints().get(1), "node2");
    Assert.assertEquals(flowEdge.getExecutors().get(0).getConfig().get().getString("specStore.fs.dir"),"/tmp1");
    Assert.assertEquals(flowEdge.getExecutors().get(0).getConfig().get().getString("specExecInstance.capabilities"),"s1:d1");
    Assert.assertEquals(flowEdge.getExecutors().get(1).getConfig().get().getString("specStore.fs.dir"),"/tmp2");
    Assert.assertEquals(flowEdge.getExecutors().get(1).getConfig().get().getString("specExecInstance.capabilities"),"s2:d2");
    Assert.assertEquals(flowEdge.getExecutors().get(0).getClass().getSimpleName(),"InMemorySpecExecutor");
    Assert.assertEquals(flowEdge.getExecutors().get(1).getClass().getSimpleName(),"InMemorySpecExecutor");
  }
}