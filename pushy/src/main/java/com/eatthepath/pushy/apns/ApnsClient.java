/*
 * Copyright (c) 2020 Jon Chambers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.eatthepath.pushy.apns;

import com.eatthepath.pushy.apns.util.concurrent.PushNotificationFuture;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>An APNs client sends push notifications to the APNs gateway. Clients authenticate themselves to APNs servers in
 * one of two ways: they may either present a TLS certificate to the server at connection time, or they may present
 * authentication tokens for each notification they send. Clients that opt to use TLS-based authentication may send
 * notifications to any topic named in the client certificate. Clients that opt to use token-based authentication may
 * send notifications to any topic associated with the team to which the client's signing key belongs. Please see the
 * <a href="https://developer.apple.com/documentation/usernotifications">UserNotifications Framework documentation</a>
 * for a detailed discussion of the APNs protocol, topics, and certificate/key provisioning.</p>
 *
 * <p>Clients are constructed using an {@link ApnsClientBuilder}. Callers may optionally specify a set of
 * {@link ApnsClientResources} when constructing a new client. If no client resources are specified,
 * clients will create and manage their own resources with a single-thread event loop group. If many clients are
 * operating in parallel, specifying a shared ser of resources serves as a mechanism to keep the total number of threads
 * in check. Callers may also want to provide a specific event loop group to take advantage of platform-specific
 * features (i.e. {@code epoll} or {@code KQueue}).</p>
 *
 * <p>Callers must either provide an SSL context with the client's certificate or a signing key at client construction
 * time. If a signing key is provided, the client will use token authentication when sending notifications; otherwise,
 * it will use TLS-based authentication. It is an error to provide both a client certificate and a signing key.</p>
 *
 * <p>Clients maintain their own internal connection pools and open connections to the APNs server on demand. As a
 * result, clients do <em>not</em> need to be "started" explicitly, and are ready to begin sending notifications as soon
 * as they're constructed.</p>
 *
 * <p>Notifications sent by a client to an APNs server are sent asynchronously. A
 * {@link CompletableFuture} is returned immediately when a notification is sent, but will not complete until the
 * attempt to send the notification has failed, the notification has been accepted by the APNs server, or the
 * notification has been rejected by the APNs server.</p>
 *
 * <p>APNs clients are intended to be long-lived, persistent resources. They are also inherently thread-safe and can be
 * shared across many threads in a complex application. Callers must shut them down via the {@link ApnsClient#close()}
 * method when they are no longer needed (i.e. when shutting down the entire application). If a set of client resources
 * was provided at construction time, callers should shut down that resource set when all clients using that group have
 * been disconnected (see {@link ApnsClientResources#shutdownGracefully()}).</p>
 *
 * @author <a href="https://github.com/jchambers">Jon Chambers</a>
 *
 * @since 0.5
 */
public class ApnsClient {
    private final ApnsClientResources clientResources;
    private final boolean shouldShutDownClientResources;

    private final ApnsChannelPool channelPool;

    private final ApnsClientMetricsListener metricsListener;

    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    private static final IllegalStateException CLIENT_CLOSED_EXCEPTION =
            new IllegalStateException("Client has been closed and can no longer send push notifications.");

    private static final Logger log = LoggerFactory.getLogger(ApnsClient.class);

    private static class NoopApnsClientMetricsListener implements ApnsClientMetricsListener {


        @Override
        public void handleWriteFailure(final String topic) {
        }

        @Override
        public void handleNotificationSent(final String topic) {
        }

        @Override
        public void handleNotificationAcknowledged(final PushNotificationResponse<?> response, final long durationNanos) {
        }

        @Override
        public void handleConnectionAdded() {
        }

        @Override
        public void handleConnectionRemoved() {
        }

        @Override
        public void handleConnectionCreationFailed() {
        }
    }

    ApnsClient(final ApnsClientConfiguration clientConfiguration, final ApnsClientResources clientResources) {

        if (clientResources != null) {
            this.clientResources = clientResources;
            this.shouldShutDownClientResources = false;
        } else {
            this.clientResources = new ApnsClientResources(new NioEventLoopGroup(1));
            this.shouldShutDownClientResources = true;
        }

        this.metricsListener = clientConfiguration.getMetricsListener()
                .orElseGet(NoopApnsClientMetricsListener::new);

        final ApnsChannelFactory channelFactory = new ApnsChannelFactory(clientConfiguration, this.clientResources);

        final ApnsChannelPoolMetricsListener channelPoolMetricsListener = new ApnsChannelPoolMetricsListener() {

            @Override
            public void handleConnectionAdded() {
                ApnsClient.this.metricsListener.handleConnectionAdded();
            }

            @Override
            public void handleConnectionRemoved() {
                ApnsClient.this.metricsListener.handleConnectionRemoved();
            }

            @Override
            public void handleConnectionCreationFailed() {
                ApnsClient.this.metricsListener.handleConnectionCreationFailed();
            }
        };

        this.channelPool = new ApnsChannelPool(channelFactory,
            clientConfiguration.getConcurrentConnections(),
            clientConfiguration.getMaxPendingAcquisition(),
            this.clientResources.getEventLoopGroup().next(),
            channelPoolMetricsListener);
    }

    /**
     * <p>Sends a push notification to the APNs gateway.</p>
     *
     * <p>This method returns a {@code Future} that indicates whether the notification was accepted or rejected by the
     * gateway. If the notification was accepted, it may be delivered to its destination device at some time in the
     * future, but final delivery is not guaranteed. Rejections should be considered permanent failures, and callers
     * should <em>not</em> attempt to re-send the notification.</p>
     *
     * <p>The returned {@code Future} may fail with an exception if the notification could not be sent. Failures to
     * <em>send</em> a notification to the gateway—i.e. those that fail with exceptions—should generally be considered
     * non-permanent, and callers should attempt to re-send the notification when the underlying problem has been
     * resolved.</p>
     *
     * @param notification the notification to send to the APNs gateway
     *
     * @param <T> the type of notification to be sent
     *
     * @return a {@code Future} that will complete when the notification has been either accepted or rejected by the
     * APNs gateway
     *
     * @since 0.8
     */
    public <T extends ApnsPushNotification> PushNotificationFuture<T, PushNotificationResponse<T>> sendNotification(final T notification) {
        final PushNotificationFuture<T, PushNotificationResponse<T>> responseFuture =
                new PushNotificationFuture<>(notification);

        if (!this.isClosed.get()) {
            final long start = System.nanoTime();

            this.channelPool.acquire().addListener((GenericFutureListener<Future<Channel>>) acquireFuture -> {
                if (acquireFuture.isSuccess()) {
                    final Channel channel = acquireFuture.getNow();

                    channel.writeAndFlush(responseFuture).addListener((GenericFutureListener<ChannelFuture>) future -> {
                        if (future.isSuccess()) {
                            ApnsClient.this.metricsListener.handleNotificationSent(notification.getTopic());
                        }
                    });

                    ApnsClient.this.channelPool.release(channel);
                } else {
                    responseFuture.completeExceptionally(acquireFuture.cause());
                }
            });

            responseFuture.whenComplete((response, cause) -> {
                final long end = System.nanoTime();

                if (response != null) {
                    ApnsClient.this.metricsListener.handleNotificationAcknowledged(response, end - start);
                } else {
                    ApnsClient.this.metricsListener.handleWriteFailure(notification.getTopic());
                }
            });
        } else {
            responseFuture.completeExceptionally(CLIENT_CLOSED_EXCEPTION);
        }

        return responseFuture;
    }

    /**
     * <p>Gracefully shuts down the client, closing all connections and releasing all persistent resources. The
     * disconnection process will wait until notifications that have been sent to the APNs server have been either
     * accepted or rejected. Note that some notifications passed to
     * {@link ApnsClient#sendNotification(ApnsPushNotification)} may still be enqueued and not yet sent by the time the
     * shutdown process begins; the {@code Futures} associated with those notifications will fail.</p>
     *
     * <p>The returned {@code Future} will be marked as complete when all connections in this client's pool have closed
     * completely and (if no {@code ApnsClientResources} were provided at construction time) the client's resources have
     * shut down. If the client has already shut down, the returned {@code Future} will be marked as complete
     * immediately.</p>
     *
     * <p>Clients may not be reused once they have been closed.</p>
     *
     * @return a {@code Future} that will be marked as complete when the client has finished shutting down
     *
     * @since 0.11
     */
    public CompletableFuture<Void> close() {
        log.info("Shutting down.");

        final CompletableFuture<Void> closeFuture;

        if (this.isClosed.compareAndSet(false, true)) {
            closeFuture = new CompletableFuture<>();

            this.channelPool.close().addListener((GenericFutureListener<Future<Void>>) closePoolFuture -> {
                if (ApnsClient.this.shouldShutDownClientResources) {
                    ApnsClient.this.clientResources.shutdownGracefully().addListener(future -> closeFuture.complete(null));
                } else {
                    closeFuture.complete(null);
                }
            });
        } else {
            closeFuture = CompletableFuture.completedFuture(null);
        }

        return closeFuture;
    }
}
