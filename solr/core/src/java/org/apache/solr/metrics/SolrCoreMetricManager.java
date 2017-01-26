/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.metrics;

import java.io.Closeable;
import java.io.IOException;
import java.lang.invoke.MethodHandles;

import org.apache.solr.cloud.CloudDescriptor;
import org.apache.solr.core.NodeConfig;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrInfoMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsible for collecting metrics from {@link SolrMetricProducer}'s
 * and exposing metrics to {@link SolrMetricReporter}'s.
 */
public class SolrCoreMetricManager implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final SolrCore core;
  private final SolrMetricManager metricManager;
  private String registryName;
  private String collectionName;
  private String shardName;
  private String replicaName;
  private String leaderRegistryName;
  private boolean cloudMode;

  /**
   * Constructs a metric manager.
   *
   * @param core the metric manager's core
   */
  public SolrCoreMetricManager(SolrCore core) {
    this.core = core;
    this.metricManager = core.getCoreDescriptor().getCoreContainer().getMetricManager();
    initCloudMode();
    registryName = createRegistryName(cloudMode, collectionName, shardName, replicaName, core.getName());
    leaderRegistryName = createLeaderRegistryName(cloudMode, collectionName, shardName);
  }

  private void initCloudMode() {
    CloudDescriptor cd = core.getCoreDescriptor().getCloudDescriptor();
    if (cd != null) {
      cloudMode = true;
      collectionName = core.getCoreDescriptor().getCollectionName();
      shardName = cd.getShardId();
      replicaName = cd.getCoreNodeName();
    }
  }

  /**
   * Load reporters configured globally and specific to {@link org.apache.solr.core.SolrInfoMBean.Group#core}
   * group or with a registry name specific to this core.
   */
  public void loadReporters() {
    NodeConfig nodeConfig = core.getCoreDescriptor().getCoreContainer().getConfig();
    PluginInfo[] pluginInfos = nodeConfig.getMetricReporterPlugins();
    metricManager.loadReporters(pluginInfos, core.getResourceLoader(), SolrInfoMBean.Group.core, registryName);
    if (cloudMode) {
      metricManager.loadReplicaReporters(pluginInfos, core, leaderRegistryName, registryName);
    }
  }

  /**
   * Make sure that metrics already collected that correspond to the old core name
   * are carried over and will be used under the new core name.
   * This method also reloads reporters so that they use the new core name.
   */
  public void afterCoreSetName() {
    String oldRegistryName = registryName;
    String oldLeaderRegistryName = leaderRegistryName;
    initCloudMode();
    registryName = createRegistryName(cloudMode, collectionName, shardName, replicaName, core.getName());
    leaderRegistryName = createLeaderRegistryName(cloudMode, collectionName, shardName);
    if (oldRegistryName.equals(registryName)) {
      return;
    }
    // close old reporters
    metricManager.closeReporters(oldRegistryName);
    metricManager.moveMetrics(oldRegistryName, registryName, null);
    // old registry is no longer used - we have moved the metrics
    metricManager.removeRegistry(oldRegistryName);
    if (oldLeaderRegistryName != null) {
      metricManager.closeReporters(oldLeaderRegistryName);
      if (leaderRegistryName != null) {
        metricManager.moveMetrics(oldLeaderRegistryName, leaderRegistryName, null);
        metricManager.removeRegistry(oldLeaderRegistryName);
      }
    }
    // load reporters again, using the new core name
    loadReporters();
  }

  /**
   * Registers a mapping of name/metric's with the manager's metric registry.
   *
   * @param scope     the scope of the metrics to be registered (e.g. `/admin/ping`)
   * @param producer  producer of metrics to be registered
   */
  public void registerMetricProducer(String scope, SolrMetricProducer producer) {
    if (scope == null || producer == null) {
      throw new IllegalArgumentException("registerMetricProducer() called with illegal arguments: " +
          "scope = " + scope + ", producer = " + producer);
    }
    producer.initializeMetrics(metricManager, getRegistryName(), scope);
  }

  /**
   * Closes reporters specific to this core.
   */
  @Override
  public void close() throws IOException {
    metricManager.closeReporters(getRegistryName());
  }

  public SolrCore getCore() {
    return core;
  }

  /**
   * Metric registry name of the manager.
   *
   * In order to make it easier for reporting tools to aggregate metrics from
   * different cores that logically belong to a single collection we convert the
   * core name into a dot-separated hierarchy of: collection name, shard name (with optional split)
   * and replica name.
   *
   * <p>For example, when the core name looks like this but it's NOT a SolrCloud collection:
   * <code>my_collection_shard1_1_replica1</code> then this will be used as the registry name (plus
   * the required <code>solr.core</code> prefix). However,
   * if this is a SolrCloud collection <code>my_collection</code> then the registry name will become
   * <code>solr.core.my_collection.shard1_1.replica1</code>.</p>
   *
   *
   * @return the metric registry name of the manager.
   */
  public String getRegistryName() {
    return registryName;
  }

  /**
   * Metric registry name for leader metrics. This is null if not in cloud mode.
   * @return metric registry name for leader metrics
   */
  public String getLeaderRegistryName() {
    return leaderRegistryName;
  }

  public static String createRegistryName(boolean cloud, String collectionName, String shardName, String replicaName, String coreName) {
    if (collectionName == null) {
      // single core
      return SolrMetricManager.getRegistryName(SolrInfoMBean.Group.core, coreName);
    }
    if (cloud) { // build registry name from logical names
      return SolrMetricManager.getRegistryName(SolrInfoMBean.Group.core, collectionName, shardName, replicaName);
    } else {
      if (!coreName.startsWith(collectionName)) {
        // unknown naming scheme
        return SolrMetricManager.getRegistryName(SolrInfoMBean.Group.core, coreName);
      }
      String shard;
      String replica = null;
      // split "collection1_shard1_1_replica1" into parts
      String str = coreName.substring(collectionName.length() + 1);
      int pos = str.lastIndexOf("_replica");
      if (pos == -1) { // ?? no _replicaN part ??
        shard = str;
      } else {
        shard = str.substring(0, pos);
        replica = str.substring(pos + 1);
      }
      return SolrMetricManager.getRegistryName(SolrInfoMBean.Group.core, collectionName, shard, replica);
    }
  }

  public static String createLeaderRegistryName(boolean cloud, String collectionName, String shardName) {
    if (cloud) {
      return createRegistryName(cloud, collectionName, shardName, "leader", null);
    } else {
      return null;
    }
  }
}
