package org.owntracks.android.services;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.test.espresso.idling.CountingIdlingResource;

import org.owntracks.android.R;
import org.owntracks.android.data.EndpointState;
import org.owntracks.android.data.repos.ContactsRepo;
import org.owntracks.android.data.repos.EndpointStateRepo;
import org.owntracks.android.data.repos.WaypointsRepo;
import org.owntracks.android.model.messages.MessageBase;
import org.owntracks.android.model.messages.MessageCard;
import org.owntracks.android.model.messages.MessageClear;
import org.owntracks.android.model.messages.MessageCmd;
import org.owntracks.android.model.messages.MessageLocation;
import org.owntracks.android.model.messages.MessageTransition;
import org.owntracks.android.services.worker.Scheduler;
import org.owntracks.android.support.Parser;
import org.owntracks.android.support.Preferences;
import org.owntracks.android.support.RunThingsOnOtherThreads;
import org.owntracks.android.support.ServiceBridge;
import org.owntracks.android.support.interfaces.ConfigurationIncompleteException;
import org.owntracks.android.support.interfaces.StatefulServiceMessageProcessor;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;
import dagger.hilt.android.qualifiers.ApplicationContext;
import timber.log.Timber;

@Singleton
public class MessageProcessor implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final ContactsRepo contactsRepo;
    private final WaypointsRepo waypointsRepo;
    private final Context applicationContext;
    private final Preferences preferences;
    private final Parser parser;
    private final Scheduler scheduler;
    private final Lazy<LocationProcessor> locationProcessorLazy;

    private final ServiceBridge serviceBridge;
    private final CountingIdlingResource outgoingQueueIdlingResource;
    private final EndpointStateRepo endpointStateRepo;
    private final RunThingsOnOtherThreads runThingsOnOtherThreads;
    private MessageProcessorEndpoint endpoint;

    private final BlockingDeque<MessageBase> outgoingQueue;
    private Thread backgroundDequeueThread;

    private static final long SEND_FAILURE_BACKOFF_INITIAL_WAIT = TimeUnit.SECONDS.toMillis(1);
    private static final long SEND_FAILURE_BACKOFF_MAX_WAIT = TimeUnit.MINUTES.toMillis(2);

    private final Object locker = new Object();
    private ScheduledFuture<?> waitFuture = null;
    private long retryWait = SEND_FAILURE_BACKOFF_INITIAL_WAIT;

    private final MutableLiveData<MessageTransition> lastTransitionMessage = new MutableLiveData<>();

    @Inject
    public MessageProcessor(
            @ApplicationContext Context applicationContext,
            ContactsRepo contactsRepo,
            Preferences preferences,
            WaypointsRepo waypointsRepo,
            Parser parser,
            Scheduler scheduler,
            ServiceBridge serviceBridge,
            RunThingsOnOtherThreads runThingsOnOtherThreads,
            CountingIdlingResource outgoingQueueIdlingResource,
            Lazy<LocationProcessor> locationProcessorLazy,
            EndpointStateRepo endpointStateRepo
    ) {
        this.applicationContext = applicationContext;
        this.preferences = preferences;
        this.contactsRepo = contactsRepo;
        this.waypointsRepo = waypointsRepo;
        this.parser = parser;
        this.scheduler = scheduler;
        this.locationProcessorLazy = locationProcessorLazy;
        this.serviceBridge = serviceBridge;
        this.outgoingQueueIdlingResource = outgoingQueueIdlingResource;
        this.endpointStateRepo = endpointStateRepo;
        this.runThingsOnOtherThreads = runThingsOnOtherThreads;

        preferences.registerOnPreferenceChangedListener(this);

        outgoingQueue = new BlockingDequeThatAlsoSometimesPersistsThingsToDiskMaybe(10000, applicationContext.getFilesDir(), parser);
        synchronized (outgoingQueue) {
            for (int i = 0; i < outgoingQueue.size(); i++) {
                outgoingQueueIdlingResource.increment();
            }
            Timber.d("Initializing the outgoingqueueidlingresource at %s", outgoingQueue.size());
        }
        onEndpointStateChanged(EndpointState.INITIAL);
        reconnect();
    }

    public void reconnect() {
        Semaphore lock = new Semaphore(1);
        lock.acquireUninterruptibly();
        reconnect(lock);
    }

    public void reconnect(@NonNull Semaphore completionNotifier) {
        if (endpoint == null) {
            loadOutgoingMessageProcessor(preferences.getMode()); // The processor should take care of the reconnect on init
        } else if (endpoint instanceof MessageProcessorEndpointMqtt) {
            ((MessageProcessorEndpointMqtt) endpoint).reconnect(completionNotifier);
        } else {
            completionNotifier.release();
        }
    }

    public boolean statefulReconnectAndSendKeepalive() {
        if (endpoint == null)
            loadOutgoingMessageProcessor(preferences.getMode());

        if (endpoint instanceof MessageProcessorEndpointMqtt) {
            Semaphore lock = new Semaphore(1);
            lock.acquireUninterruptibly();
            ((MessageProcessorEndpointMqtt) endpoint).reconnectAndSendKeepalive(lock);
            try {
                // Above method may re-trigger itself on a different thread, so we want to wait until
                // that's complete.
                Timber.d("Waiting for reconnect worker to complete");
                lock.acquire();
                Timber.d("Completed");
                return true;
            } catch (InterruptedException e) {
                Timber.w(e, "Interrupted waiting for reconnect future to complete");
                return false;
            }
        } else {
            return true;
        }
    }

    public boolean statefulCheckConnection() {
        if (endpoint == null)
            loadOutgoingMessageProcessor(preferences.getMode());

        if (endpoint instanceof StatefulServiceMessageProcessor)
            return ((StatefulServiceMessageProcessor) endpoint).checkConnection();
        else
            return true;
    }

    public boolean isEndpointConfigurationComplete() {
        try {
            if (this.endpoint != null) {
                this.endpoint.checkConfigurationComplete();
                return true;
            }
        } catch (ConfigurationIncompleteException e) {
            return false;
        }
        return false;
    }

    private void loadOutgoingMessageProcessor(int mode) {

        if (this.endpoint != null) {
            if ((mode == MessageProcessorEndpointHttp.MODE_ID && endpoint instanceof MessageProcessorEndpointHttp)
                    || (mode == MessageProcessorEndpointMqtt.MODE_ID && endpoint instanceof MessageProcessorEndpointMqtt)) {
                Timber.i("New requested mode is same as current endpoint. Not reloading.");
                return;
            }
        }
        Timber.d("Reloading outgoing message processor to %s. ThreadID: %s", preferences.getMode(), Thread.currentThread());
        if (endpoint != null) {
            endpoint.onDestroy();
        }

        endpointStateRepo.setQueueLength(outgoingQueue.size());

        switch (mode) {
            case MessageProcessorEndpointHttp.MODE_ID:
                this.endpoint = new MessageProcessorEndpointHttp(this, this.parser, this.preferences, this.scheduler, this.applicationContext);
                break;
            case MessageProcessorEndpointMqtt.MODE_ID:
            default:
                this.endpoint = new MessageProcessorEndpointMqtt(this, this.parser, this.preferences, this.scheduler, this.runThingsOnOtherThreads, this.applicationContext, this.contactsRepo);

        }

        if (backgroundDequeueThread == null || !backgroundDequeueThread.isAlive()) {
            // Create the background thread that will handle outbound msgs
            backgroundDequeueThread = new Thread(this::sendAvailableMessages, "backgroundDequeueThread");
            backgroundDequeueThread.start();
        }

        this.endpoint.onCreateFromProcessor();
    }

    public void queueMessageForSending(MessageBase message) {
        outgoingQueueIdlingResource.increment();
        Timber.d("Queueing messageId:%s, queueLength:%s, ThreadID: %s", message.getMessageId(), outgoingQueue.size(), Thread.currentThread());
        synchronized (outgoingQueue) {
            if (!outgoingQueue.offer(message)) {
                MessageBase droppedMessage = outgoingQueue.poll();
                Timber.e("Outgoing queue full. Dropping oldest message: %s", droppedMessage);
                if (!outgoingQueue.offer(message)) {
                    Timber.e("Still can't put message onto the queue. Dropping: %s", message);
                }
            }
        }
        endpointStateRepo.setQueueLength(outgoingQueue.size());
    }

    // Should be on the background thread here, because we block
    private void sendAvailableMessages() {
        Timber.d("Starting outbound message loop. ThreadID: %s", Thread.currentThread());
        boolean previousMessageFailed = false;
        int retriesToGo = 0;

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        while (true) {
            try {
                MessageBase message;
                message = this.outgoingQueue.take(); // <--- blocks
                if (!previousMessageFailed) {
                    retriesToGo = message.getNumberOfRetries();
                }

                /*
                We need to run the actual network sending part on a different thread because the
                implementation might not be thread-safe. So we wrap `sendMessage()` up in a callable
                and a FutureTask and then dispatch it off to the network thread, and block on the
                return, handling any exceptions that might have been thrown.
                */
                Callable<Void> sendMessageCallable = () -> {
                    this.endpoint.sendMessage(message);
                    return null;
                };
                FutureTask<Void> futureTask = new FutureTask<>(sendMessageCallable);
                runThingsOnOtherThreads.postOnNetworkHandlerDelayed(futureTask, 1);
                try {
                    try {
                        futureTask.get();
                    } catch (ExecutionException e) {
                        if (e.getCause() != null) {
                            throw e.getCause();
                        } else {
                            throw new Exception("sendMessage failed, but no exception actually given");
                        }
                    }
                    previousMessageFailed = false;
                    retryWait = SEND_FAILURE_BACKOFF_INITIAL_WAIT;
                } catch (OutgoingMessageSendingException | ConfigurationIncompleteException e) {
                    Timber.w(("Error sending message. Re-queueing"));
                    // Let's do a little dance to hammer this failed message back onto the head of the
                    // queue. If someone's queued something on the tail in the meantime and the queue
                    // is now empty, then throw that latest message away.
                    synchronized (this.outgoingQueue) {
                        if (!this.outgoingQueue.offerFirst(message)) {
                            MessageBase tailMessage = this.outgoingQueue.removeLast();
                            Timber.w("Queue full when trying to re-queue failed message. Dropping last message: %s", tailMessage);
                            if (!this.outgoingQueue.offerFirst(message)) {
                                Timber.e("Couldn't restore failed message back onto the head of the queue, dropping: %s", message);
                            }
                        }
                    }
                    previousMessageFailed = true;
                    retriesToGo -= 1;
                } catch (IOException e) {
                    retryWait = SEND_FAILURE_BACKOFF_INITIAL_WAIT;
                    previousMessageFailed = false;
                    // Deserialization failure, drop and move on
                } catch (Throwable e) {
                    Timber.e(e, "Unhandled exception in sending message");
                    previousMessageFailed = false;
                }

                if (previousMessageFailed && retriesToGo <= 0) {
                    previousMessageFailed = false;
                }
                if (previousMessageFailed) {
                    Timber.i("Waiting for %s s before retrying", retryWait / 1000);
                    waitFuture = scheduler.schedule(() -> {
                        synchronized (locker) {
                            locker.notify();
                        }
                    }, retryWait, TimeUnit.MILLISECONDS);
                    synchronized (locker) {
                        locker.wait();
                    }
                    retryWait = Math.min(2 * retryWait, SEND_FAILURE_BACKOFF_MAX_WAIT);
                } else {
                    synchronized (outgoingQueueIdlingResource) {
                        try {
                            if (!outgoingQueueIdlingResource.isIdleNow()) {
                                outgoingQueueIdlingResource.decrement();
                            }
                        } catch (IllegalStateException e) {
                            Timber.w(e, "outgoingQueueIdlingResource is invalid");
                        }
                    }
                }
            } catch (InterruptedException e) {
                Timber.i(e, "Outgoing message loop interrupted");
                break;
            }
        }
        scheduler.shutdown();
        Timber.w("Exiting outgoingmessage loop");
    }

    public void resetMessageSleepBlock() {
        if (waitFuture != null && waitFuture.cancel(false)) {
            Timber.d("Resetting message send loop wait. Thread: %s", Thread.currentThread());
            retryWait = SEND_FAILURE_BACKOFF_INITIAL_WAIT;
            synchronized (locker) {
                locker.notify();
            }
        }
    }

    void onMessageDelivered(MessageBase messageBase) {
        Timber.d("onMessageDelivered in MessageProcessor Noop. ThreadID: %s", Thread.currentThread());
        endpointStateRepo.setQueueLength(outgoingQueue.size());
    }

    void onMessageDeliveryFailedFinal(String messageId) {
        Timber.e("Message delivery failed, not retryable. :%s", messageId);
        endpointStateRepo.setQueueLength(outgoingQueue.size());
    }

    void onMessageDeliveryFailed(String messageId) {
        Timber.e("Message delivery failed. queueLength: %s, messageId: %s", outgoingQueue.size() + 1, messageId);
        endpointStateRepo.setQueueLength(outgoingQueue.size() + 1); // Failed message hasn't been re-queued yet, so add 1
    }

    void onEndpointStateChanged(EndpointState newState) {
        Timber.d("message: %s %s %s", newState, newState.getMessage(), Thread.currentThread());
        endpointStateRepo.setState(newState);
    }

    public void processIncomingMessage(MessageBase message) {
        Timber.i("Received incoming message: %s on %s", message.getClass().getSimpleName(), message.getContactKey());
        if (message instanceof MessageClear) {
            processIncomingMessage((MessageClear) message);
        } else if (message instanceof MessageLocation) {
            processIncomingMessage((MessageLocation) message);
        } else if (message instanceof MessageCard) {
            processIncomingMessage((MessageCard) message);
        } else if (message instanceof MessageCmd) {
            processIncomingMessage((MessageCmd) message);
        } else if (message instanceof MessageTransition) {
            processIncomingMessage((MessageTransition) message);
        }
    }

    private void processIncomingMessage(MessageTransition message) {
        if (message.isIncoming()) {
            lastTransitionMessage.postValue(message);
        }
    }

    private void processIncomingMessage(MessageClear message) {
        contactsRepo.remove(message.getContactKey());
    }

    private void processIncomingMessage(MessageLocation message) {
        // do not use TimeUnit.DAYS.toMillis to avoid long/double conversion issues...
        if ((preferences.getIgnoreStaleLocations() > 0) && (System.currentTimeMillis() - ((message).getTimestamp() * 1000)) > (preferences.getIgnoreStaleLocations() * 24 * 60 * 60 * 1000)) {
            Timber.e("discarding stale location");
            return;
        }
        contactsRepo.update(message.getContactKey(), message);
    }

    private void processIncomingMessage(MessageCard message) {
        contactsRepo.update(message.getContactKey(), message);
    }

    private void processIncomingMessage(MessageCmd message) {
        if (!preferences.getRemoteCommand()) {
            Timber.w("remote commands are disabled");
            return;
        }

        if (message.getModeId() != MessageProcessorEndpointHttp.MODE_ID &&
                !preferences.getPubTopicCommands().equals(message.getTopic())
        ) {
            Timber.e("cmd message received on wrong topic");
            return;
        }

        if (!message.isValidMessage()) {
            Timber.e("Invalid action message received");
            return;
        }
        if (message.getAction() != null) {
            switch (message.getAction()) {
                case REPORT_LOCATION:
                    if (message.getModeId() != MessageProcessorEndpointMqtt.MODE_ID) {
                        Timber.e("command not supported in HTTP mode: %s", message.getAction());
                        break;
                    }
                    serviceBridge.requestOnDemandLocationFix();

                    break;
                case WAYPOINTS:
                    locationProcessorLazy.get().publishWaypointsMessage();
                    break;
                case SET_WAYPOINTS:
                    if (message.getWaypoints() != null) {
                        waypointsRepo.importFromMessage(message.getWaypoints().getWaypoints());
                    }

                    break;
                case SET_CONFIGURATION:
                    if (!preferences.getRemoteConfiguration()) {
                        Timber.w("Received a remote configuration command but remote config setting is disabled");
                        break;
                    }
                    if (message.getConfiguration() != null) {
                        preferences.importFromMessage(message.getConfiguration());
                    } else {
                        Timber.w("No configuration provided");
                    }
                    if (message.getWaypoints() != null) {
                        waypointsRepo.importFromMessage(message.getWaypoints().getWaypoints());
                    }
                    break;
                case RECONNECT:
                    if (message.getModeId() != MessageProcessorEndpointHttp.MODE_ID) {
                        Timber.e("command not supported in HTTP mode: %s", message.getAction());
                        break;
                    }
                    reconnect();
                    break;
                default:
                    break;
            }
        }
    }

    public void stopSendingMessages() {
        Timber.d("Interrupting background sending thread");
        backgroundDequeueThread.interrupt();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (Objects.equals(key, applicationContext.getString(R.string.preferenceKeyModeId))) {
            loadOutgoingMessageProcessor(preferences.getMode());
        }
    }

    public LiveData<MessageTransition> getLastTransitionMessage() {
        return lastTransitionMessage;
    }
}