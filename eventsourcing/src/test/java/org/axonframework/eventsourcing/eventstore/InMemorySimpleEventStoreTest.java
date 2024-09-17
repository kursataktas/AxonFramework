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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.eventhandling.EventMessage;
import org.axonframework.messaging.MessageStream;
import org.axonframework.messaging.unitofwork.AsyncUnitOfWork;
import org.junit.jupiter.api.*;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.axonframework.eventsourcing.eventstore.SourcingCondition.conditionFor;
import static org.junit.jupiter.api.Assertions.*;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

/**
 * Test class validating the {@link SimpleEventStore} together with the {@link AsyncInMemoryEventStorageEngine}.
 *
 * @author Steven van Beelen
 */
class InMemorySimpleEventStoreTest extends SimpleEventStoreTestSuite<AsyncInMemoryEventStorageEngine> {

    @Override
    protected AsyncInMemoryEventStorageEngine buildStorageEngine() {
        return new AsyncInMemoryEventStorageEngine(Clock.systemUTC());
    }

    /**
     * By sourcing twice within a given UnitOfWork, the DefaultEventStoreTransaction combines the AppendConditions. By
     * following this up with an appendEvent invocation, the in-memory EventStorageEngine will throw an
     * AppendConditionAssertionException as intended.
     */
    @Test
    void appendEventsThrowsAppendConditionAssertionExceptionWhenToManyIndicesAreGiven() {
        SourcingCondition firstCondition = conditionFor(TEST_AGGREGATE_INDEX);
        SourcingCondition secondCondition = conditionFor(new Index("aggregateId", "other-aggregate-id"));
        AtomicReference<MessageStream<EventMessage<?>>> streamReference = new AtomicReference<>();

        EventMessage<?> testEvent = eventMessage(0);

        AsyncUnitOfWork uow = new AsyncUnitOfWork();
        uow.runOnPreInvocation(context -> {
               EventStoreTransaction transaction = testSubject.transaction(context, TEST_CONTEXT);
               MessageStream<EventMessage<?>> firstStream = transaction.source(firstCondition, context);
               MessageStream<EventMessage<?>> secondStream = transaction.source(secondCondition, context);
               streamReference.set(firstStream.concatWith(secondStream));
           })
           .runOnPostInvocation(context -> {
               EventStoreTransaction transaction = testSubject.transaction(context, TEST_CONTEXT);
               transaction.appendEvent(testEvent);
           });

        CompletableFuture<Void> result = uow.execute();

        await().atMost(Duration.ofMillis(500))
               .pollDelay(Duration.ofMillis(25))
               .untilAsserted(() -> {
                   assertTrue(result.isCompletedExceptionally());
                   assertInstanceOf(AppendConditionAssertionException.class, result.exceptionNow());
               });

        // The stream should be entirely empty, as two non-existing models are sourced.
        assertNotNull(streamReference.get());
        StepVerifier.create(streamReference.get().asFlux())
                    .verifyComplete();
    }
}
