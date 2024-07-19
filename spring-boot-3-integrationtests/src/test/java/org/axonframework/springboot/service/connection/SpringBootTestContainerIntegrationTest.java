/*
 * Copyright (c) 2010-2024. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.springboot.service.connection;

import io.axoniq.axonserver.grpc.admin.ContextOverview;
import org.axonframework.axonserver.connector.AxonServerConfiguration;
import org.axonframework.axonserver.connector.AxonServerConnectionManager;
import org.axonframework.test.server.AxonServerContainer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class SpringBootTestContainerIntegrationTest {

    @Container
    @ServiceConnection
    private final static AxonServerContainer axonServer = new AxonServerContainer().withDevMode(true);

    @Autowired
    private AxonServerConfiguration axonServerConfiguration;

    @Autowired
    private AxonServerConnectionDetails connectionDetails;

    @Autowired
    private AxonServerConnectionManager axonServerConnectionManager;

    @Test
    void verifyApplicationStartsNormallyWithAxonServerInstance() throws ExecutionException, InterruptedException {
        assertTrue(axonServer.isRunning());
        assertNotNull(connectionDetails);
        assertTrue(connectionDetails.routingServers().endsWith("" + axonServer.getGrpcPort()));
        assertNotNull(axonServerConfiguration);

        assertNotEquals("localhost:8024", axonServerConfiguration.getServers());

        // Retrieving all contexts will try to establish a connection with the container
        CompletableFuture<List<ContextOverview>> allContexts = axonServerConnectionManager.getConnection()
                                                                                          .adminChannel()
                                                                                          .getAllContexts();
        await().atMost(Duration.ofMillis(500))
               .until(allContexts::isDone);
        assertFalse(allContexts.get().isEmpty());
    }
}