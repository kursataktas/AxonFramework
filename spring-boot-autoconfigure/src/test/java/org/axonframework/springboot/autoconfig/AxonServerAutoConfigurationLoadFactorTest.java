/*
 * Copyright (c) 2010-2023. Axon Framework
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

package org.axonframework.springboot.autoconfig;

import org.axonframework.axonserver.connector.command.CommandLoadFactorProvider;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link CommandLoadFactorProvider} is correctly auto-configured.
 *
 * @author Sara Pellegrini
 */
class AxonServerAutoConfigurationLoadFactorTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner();
    }

    @Test
    void loadFactor() {
        testContext.withUserConfiguration(TestContext.class)
                   .withPropertyValues("axon.axonserver.command-load-factor=36")
                   .run(context -> {
                       CommandLoadFactorProvider commandLoadFactorProvider =
                               context.getBean("commandLoadFactorProvider", CommandLoadFactorProvider.class);
                       int loadFactor = commandLoadFactorProvider.getFor("anything");
                       assertEquals(36, loadFactor);
                   });
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    static class TestContext {

    }
}
