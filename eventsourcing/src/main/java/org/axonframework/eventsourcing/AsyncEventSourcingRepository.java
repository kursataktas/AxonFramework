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

package org.axonframework.eventsourcing;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventsourcing.eventstore.AsyncEventStore;
import org.axonframework.eventsourcing.eventstore.EventStoreTransaction;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.messaging.unitofwork.ProcessingContext;
import org.axonframework.messaging.unitofwork.ProcessingContext.ResourceKey;
import org.axonframework.modelling.repository.AsyncRepository;
import org.axonframework.modelling.repository.ManagedEntity;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.annotation.Nonnull;

/**
 * {@link AsyncRepository} implementation that loads entities based on their historic event streams, provided by an
 * {@link AsyncEventStore}.
 *
 * @param <ID> The type of identifier used to identify the entity.
 * @param <M>  The type of the model to load.
 * @author Allard Buijze
 * @author Steven van Beelen
 * @since 0.1
 */
public class AsyncEventSourcingRepository<ID, M> implements AsyncRepository.LifecycleManagement<ID, M> {

    private final ResourceKey<Map<ID, CompletableFuture<EventSourcedEntity<ID, M>>>> managedEntitiesKey =
            ResourceKey.create("managedEntities");

    private final AsyncEventStore eventStore;
    private final IndexResolver<ID> indexResolver;
    private final EventStateApplier<M> eventStateApplier;
    // TODO rename this field to something else
    private final String contextOrNamespace;

    /**
     * Initialize the repository to load events from the given {@code eventStore} using the given {@code applier} to
     * apply state transitions to the entity based on the events received, and given {@code indexResolver} to resolve
     * the {@link org.axonframework.eventsourcing.eventstore.Index} of the given identifier type.
     *
     * @param eventStore         The event store to load events from.
     * @param indexResolver      Converts the given identifier to an
     *                           {@link org.axonframework.eventsourcing.eventstore.Index} used to load a matching event
     *                           stream.
     * @param eventStateApplier  The function to apply event state changes to the loaded entities.
     * @param contextOrNamespace
     */
    public AsyncEventSourcingRepository(AsyncEventStore eventStore,
                                        IndexResolver<ID> indexResolver,
                                        EventStateApplier<M> eventStateApplier,
                                        String contextOrNamespace) {
        this.eventStore = eventStore;
        this.indexResolver = indexResolver;
        this.eventStateApplier = eventStateApplier;
        this.contextOrNamespace = contextOrNamespace;
    }

    @Override
    public ManagedEntity<ID, M> attach(@Nonnull ManagedEntity<ID, M> entity,
                                       @Nonnull ProcessingContext processingContext) {
        var managedEntities = processingContext.computeResourceIfAbsent(managedEntitiesKey, ConcurrentHashMap::new);

        return managedEntities.computeIfAbsent(
                entity.identifier(),
                id -> {
                    EventSourcedEntity<ID, M> sourcedEntity = EventSourcedEntity.mapToEventSourcedEntity(entity);
                    updateActiveModel(sourcedEntity, processingContext);
                    return CompletableFuture.completedFuture(sourcedEntity);
                }
        ).resultNow();
    }

    @Override
    public CompletableFuture<ManagedEntity<ID, M>> load(@Nonnull ID identifier,
                                                        @Nonnull ProcessingContext processingContext,
                                                        long start,
                                                        long end) {
        var managedEntities = processingContext.computeResourceIfAbsent(managedEntitiesKey, ConcurrentHashMap::new);

        return managedEntities.computeIfAbsent(
                identifier,
                id -> eventStore.transaction(processingContext, contextOrNamespace)
                                .source(
                                        SourcingCondition.conditionFor(indexResolver.resolve(id), start, end),
                                        processingContext
                                )
                                .reduce(new EventSourcedEntity<>(identifier, (M) null), (entity, em) -> {
                                    entity.applyStateChange(em, eventStateApplier);
                                    return entity;
                                })
                                .whenComplete((entity, exception) -> {
                                    if (exception == null) {
                                        updateActiveModel(entity, processingContext);
                                    }
                                })
        ).thenApply(Function.identity());
    }

    @Override
    public CompletableFuture<ManagedEntity<ID, M>> loadOrCreate(@Nonnull ID identifier,
                                                                @Nonnull ProcessingContext processingContext,
                                                                @Nonnull Supplier<M> factoryMethod) {
        return load(identifier, processingContext).thenApply(
                managedEntity -> {
                    managedEntity.applyStateChange(
                            entity -> entity != null ? entity : factoryMethod.get()
                    );
                    return managedEntity;
                }
        );
    }

    @Override
    public ManagedEntity<ID, M> persist(@Nonnull ID identifier,
                                        @Nonnull M entity,
                                        @Nonnull ProcessingContext processingContext) {
        var managedEntities = processingContext.computeResourceIfAbsent(managedEntitiesKey, ConcurrentHashMap::new);

        return managedEntities.computeIfAbsent(identifier, id -> {
            EventSourcedEntity<ID, M> sourcedEntity = new EventSourcedEntity<>(identifier, entity);
            updateActiveModel(sourcedEntity, processingContext);
            return CompletableFuture.completedFuture(sourcedEntity);
        }).resultNow();
    }

    /**
     * Update the given {@code entity} for any event that is published within its lifecycle, by invoking the
     * {@link EventStateApplier} in the {@link EventStoreTransaction#onAppend(Consumer)}. onAppend hook is used to
     * immediately source events that are being published by the model
     *
     * @param entity            An {@link ManagedEntity} to make the state change for.
     * @param processingContext The context for which to retrieve the active {@link EventStoreTransaction}.
     */
    private void updateActiveModel(EventSourcedEntity<ID, M> entity, ProcessingContext processingContext) {
        eventStore.transaction(processingContext, contextOrNamespace)
                  .onAppend(event -> entity.applyStateChange(event, eventStateApplier));
    }

    @Override
    public void describeTo(@Nonnull ComponentDescriptor descriptor) {
        descriptor.describeProperty("eventStore", eventStore);
        descriptor.describeProperty("indexResolver", indexResolver);
        descriptor.describeProperty("eventStateApplier", eventStateApplier);
    }

    /**
     * Private implementation of the {@link ManagedEntity} supporting event sourcing.
     *
     * @param <ID> The type of identifier of the event sourced entity.
     * @param <M>  The type of entity managed by this event sourced entity.
     */
    private static class EventSourcedEntity<ID, M> implements ManagedEntity<ID, M> {

        private final ID identifier;
        private final AtomicReference<M> currentState;

        private EventSourcedEntity(ID identifier, M currentState) {
            this.identifier = identifier;
            this.currentState = new AtomicReference<>(currentState);
        }

        private static <ID, T> EventSourcedEntity<ID, T> mapToEventSourcedEntity(ManagedEntity<ID, T> entity) {
            return entity instanceof AsyncEventSourcingRepository.EventSourcedEntity<ID, T> eventSourcedEntity
                    ? eventSourcedEntity
                    : new EventSourcedEntity<>(entity.identifier(), entity.entity());
        }

        @Override
        public ID identifier() {
            return identifier;
        }

        @Override
        public M entity() {
            return currentState.get();
        }

        @Override
        public M applyStateChange(UnaryOperator<M> change) {
            return currentState.updateAndGet(change);
        }

        private M applyStateChange(EventMessage<?> event, EventStateApplier<M> applier) {
            return currentState.updateAndGet(current -> applier.changeState(current, event));
        }
    }
}
