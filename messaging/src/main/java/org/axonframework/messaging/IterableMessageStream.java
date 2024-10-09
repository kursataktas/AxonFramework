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

package org.axonframework.messaging;

import jakarta.annotation.Nonnull;
import org.axonframework.common.FutureUtils;
import reactor.core.publisher.Flux;

import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.StreamSupport;

/**
 * A {@link MessageStream} implementation using an {@link Iterable} as the source for {@link MessageEntry entries}.
 *
 * @param <M> The type of {@link Message} contained in the {@link MessageEntry entries} of this stream.
 * @author Allard Buijze
 * @author Steven van Beelen
 * @since 5.0.0
 */
class IterableMessageStream<M extends Message<?>> implements MessageStream<M> {

    private static final boolean NOT_PARALLEL = false;

    private final Iterable<MessageEntry<M>> source;

    /**
     * Constructs a {@link MessageStream stream} using the given {@code source} to provide the
     * {@link MessageEntry entries}.
     *
     * @param source The {@link Iterable} providing the {@link MessageEntry entries} for this
     *               {@link MessageStream stream}.
     */
    IterableMessageStream(@Nonnull Iterable<MessageEntry<M>> source) {
        this.source = source;
    }

    @Override
    public CompletableFuture<MessageEntry<M>> asCompletableFuture() {
        Iterator<MessageEntry<M>> iterator = source.iterator();
        return iterator.hasNext()
                ? CompletableFuture.completedFuture(iterator.next())
                : FutureUtils.emptyCompletedFuture();
    }

    @Override
    public Flux<MessageEntry<M>> asFlux() {
        return Flux.fromIterable(source);
    }

    @Override
    public <RM extends Message<?>> MessageStream<RM> map(@Nonnull Function<MessageEntry<M>, MessageEntry<RM>> mapper) {
        return new IterableMessageStream<>(
                StreamSupport.stream(source.spliterator(), NOT_PARALLEL)
                             .map(mapper)
                             .toList()
        );
    }

    @Override
    public <R> CompletableFuture<R> reduce(@Nonnull R identity,
                                           @Nonnull BiFunction<R, MessageEntry<M>, R> accumulator) {
        return CompletableFuture.completedFuture(
                StreamSupport.stream(source.spliterator(), NOT_PARALLEL)
                             .reduce(identity, accumulator, (thisResult, thatResult) -> {
                                 throw new UnsupportedOperationException(
                                         "Cannot combine reduce results as parallelized reduce is not supported."
                                 );
                             })
        );
    }
}
