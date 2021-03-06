/**
 * Copyright 2016 Yahoo Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yahoo.pulsar.broker;

import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.bookkeeper.test.PortManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.yahoo.pulsar.broker.loadbalance.LoadBalancerTest;
import com.yahoo.pulsar.broker.namespace.NamespaceService;
import com.yahoo.pulsar.client.admin.PulsarAdmin;
import com.yahoo.pulsar.client.admin.PulsarAdminException;
import com.yahoo.pulsar.client.api.Authentication;
import com.yahoo.pulsar.client.api.PulsarClientException;
import com.yahoo.pulsar.common.policies.data.BrokerAssignment;
import com.yahoo.pulsar.common.policies.data.ClusterData;
import com.yahoo.pulsar.common.policies.data.NamespaceOwnershipStatus;
import com.yahoo.pulsar.common.policies.data.PropertyAdmin;
import com.yahoo.pulsar.zookeeper.LocalBookkeeperEnsemble;

public class SLAMonitoringTest {
    LocalBookkeeperEnsemble bkEnsemble;

    ExecutorService executor = new ThreadPoolExecutor(5, 20, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());

    private static final Logger log = LoggerFactory.getLogger(LoadBalancerTest.class);

    private final int ZOOKEEPER_PORT = PortManager.nextFreePort();

    private static final int BROKER_COUNT = 5;
    private int[] brokerWebServicePorts = new int[BROKER_COUNT];
    private int[] brokerNativeBrokerPorts = new int[BROKER_COUNT];
    private URL[] brokerUrls = new URL[BROKER_COUNT];
    private String[] lookupAddresses = new String[BROKER_COUNT];
    private PulsarService[] pulsarServices = new PulsarService[BROKER_COUNT];
    private PulsarAdmin[] pulsarAdmins = new PulsarAdmin[BROKER_COUNT];
    private ServiceConfiguration[] configurations = new ServiceConfiguration[BROKER_COUNT];

    @BeforeClass
    void setup() throws Exception {
        log.info("---- Initializing SLAMonitoringTest -----");
        // Start local bookkeeper ensemble
        bkEnsemble = new LocalBookkeeperEnsemble(3, ZOOKEEPER_PORT, PortManager.nextFreePort());
        bkEnsemble.start();

        // start brokers
        for (int i = 0; i < BROKER_COUNT; i++) {
            brokerWebServicePorts[i] = PortManager.nextFreePort();
            brokerNativeBrokerPorts[i] = PortManager.nextFreePort();

            ServiceConfiguration config = new ServiceConfiguration();
            config.setBrokerServicePort(brokerNativeBrokerPorts[i]);
            config.setClusterName("my-cluster");
            config.setWebServicePort(brokerWebServicePorts[i]);
            config.setZookeeperServers("127.0.0.1" + ":" + ZOOKEEPER_PORT);
            config.setBrokerServicePort(brokerNativeBrokerPorts[i]);
            configurations[i] = config;

            pulsarServices[i] = new PulsarService(config);
            pulsarServices[i].start();

            brokerUrls[i] = new URL("http://127.0.0.1" + ":" + brokerWebServicePorts[i]);
            lookupAddresses[i] = pulsarServices[i].getHost() + ":" + config.getWebServicePort();
            pulsarAdmins[i] = new PulsarAdmin(brokerUrls[i], (Authentication) null);
        }

        Thread.sleep(100);

        createProperty(pulsarAdmins[BROKER_COUNT - 1]);
        for (int i = 0; i < BROKER_COUNT; i++) {
            String destination = String.format("%s/%s/%s:%s", NamespaceService.SLA_NAMESPACE_PROPERTY, "my-cluster",
                    pulsarServices[i].getHost(), brokerWebServicePorts[i]);
            pulsarAdmins[0].namespaces().createNamespace(destination);
        }
    }

    private void createProperty(PulsarAdmin pulsarAdmin)
            throws PulsarClientException, MalformedURLException, PulsarAdminException {
        ClusterData clusterData = new ClusterData();
        clusterData.setServiceUrl(pulsarAdmin.getServiceUrl().toString());
        pulsarAdmins[0].clusters().createCluster("my-cluster", clusterData);
        Set<String> allowedClusters = new HashSet<>();
        allowedClusters.add("my-cluster");
        PropertyAdmin adminConfig = new PropertyAdmin();
        adminConfig.setAllowedClusters(allowedClusters);
        List<String> adminRoles = new ArrayList<>();
        adminRoles.add("");
        adminConfig.setAdminRoles(adminRoles);
        pulsarAdmin.properties().createProperty("sla-monitor", adminConfig);
    }

    @AfterClass
    void shutdown() throws Exception {
        log.info("--- Shutting down ---");
        executor.shutdown();

        for (int i = 0; i < BROKER_COUNT; i++) {
            pulsarAdmins[i].close();
            pulsarServices[i].close();
        }

        bkEnsemble.stop();
    }

    @Test
    public void testOwnershipAfterSetup() {
        for (int i = 0; i < BROKER_COUNT; i++) {
            try {
                assertTrue(pulsarServices[0].getNamespaceService().registerSLANamespace());
            } catch (PulsarServerException e) {
                e.printStackTrace();
                log.error("Exception occured", e);
                fail("SLA Namespace should have been owned by the broker, Exception.", e);
            }
        }
    }

    @Test
    public void testOwnedNamespaces() {
        testOwnershipViaAdminAfterSetup();
        try {
            for (int i = 0; i < BROKER_COUNT; i++) {
                List<String> list = pulsarAdmins[i].brokers().getActiveBrokers("my-cluster");
                Assert.assertNotNull(list);
                Assert.assertEquals(list.size(), BROKER_COUNT);

                Map<String, NamespaceOwnershipStatus> nsMap = pulsarAdmins[i].brokers().getOwnedNamespaces("my-cluster",
                        list.get(0));
                Assert.assertEquals(2, nsMap.size());
            }
        } catch (Exception e) {
            e.printStackTrace();
            fail("Hearbeat namespace and SLA namespace should be owned by the broker");
        }
    }

    @Test
    public void testOwnershipViaAdminAfterSetup() {
        for (int i = 0; i < BROKER_COUNT; i++) {
            try {
                String destination = String.format("persistent://%s/%s/%s:%s/%s",
                        NamespaceService.SLA_NAMESPACE_PROPERTY, "my-cluster", pulsarServices[i].getHost(),
                        brokerWebServicePorts[i], "my-topic");
                assertEquals(pulsarAdmins[0].lookups().lookupDestination(destination),
                        "pulsar://" + pulsarServices[i].getHost() + ":" + brokerNativeBrokerPorts[i]);
            } catch (Exception e) {
                e.printStackTrace();
                fail("SLA Namespace should have been owned by the broker(" + "pulsar://" + pulsarServices[i].getHost()
                        + ":" + brokerNativeBrokerPorts[i] + ")");
            }
        }
    }

    @Test
    public void testUnloadIfBrokerCrashes() {
        int crashIndex = BROKER_COUNT / 2;
        log.info("Trying to close the broker at index = {}", crashIndex);

        try {
            pulsarServices[crashIndex].close();
        } catch (PulsarServerException e) {
            e.printStackTrace();
            fail("Should be a able to close the broker index " + crashIndex + " Exception: " + e);
        }

        String destination = String.format("persistent://%s/%s/%s:%s/%s", NamespaceService.SLA_NAMESPACE_PROPERTY,
                "my-cluster", pulsarServices[crashIndex].getHost(), brokerWebServicePorts[crashIndex], "my-topic");

        log.info("Lookup for namespace {}", destination);

        String broker = null;
        try {
            broker = pulsarAdmins[BROKER_COUNT - 1].lookups().lookupDestination(destination);
            log.info("{} Namespace is owned by {}", destination, broker);
            assertNotEquals(broker,
                    "pulsar://" + pulsarServices[crashIndex].getHost() + ":" + brokerNativeBrokerPorts[crashIndex]);
        } catch (PulsarAdminException e) {
            e.printStackTrace();
            fail("The SLA Monitor namespace should be owned by some other broker");
        }

        // Check if the namespace is properly unloaded and reowned by the broker
        try {
            pulsarServices[crashIndex] = new PulsarService(configurations[crashIndex]);
            pulsarServices[crashIndex].start();
            assertEquals(pulsarServices[crashIndex].getConfiguration().getBrokerServicePort(),
                    brokerNativeBrokerPorts[crashIndex]);
        } catch (PulsarServerException e) {
            e.printStackTrace();
            fail("The broker should be able to start without exception");
        }

        try {
            broker = pulsarAdmins[0].lookups().lookupDestination(destination);
            log.info("{} Namespace is re-owned by {}", destination, broker);
            assertEquals(broker,
                    "pulsar://" + pulsarServices[crashIndex].getHost() + ":" + brokerNativeBrokerPorts[crashIndex]);
        } catch (PulsarAdminException e) {
            e.printStackTrace();
            fail("The SLA Monitor namespace should be reowned by the broker" + broker);
        }

        try {
            pulsarServices[crashIndex].close();
        } catch (PulsarServerException e) {
            e.printStackTrace();
            fail("The broker should be able to stop without exception");
        }
    }

}
