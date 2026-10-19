package com.districompute.network;

import com.districompute.client.ClientSession;
import com.districompute.common.models.NetworkMessage;
import com.districompute.common.models.NetworkMessage.Type;
import com.districompute.common.patterns.Observable;
import com.districompute.common.patterns.Observer;

import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Manages the TCP connection from the client to the DistriCompute server.
 *
 * - One listener thread reads ALL incoming messages from the server.
 * - Request/response messages are routed via a BlockingQueue to sendRequest().
 * - Push messages (task updates, notifications) are routed to observers.
 *
 * This cleanly solves the race condition where the listener thread and
 * sendRequest() were both competing to read from the same ObjectInputStream.
 */
public class ServerConnection implements Observable<NetworkMessage> {

    private static final Logger LOGGER = Logger.getLogger(ServerConnection.class.getName());
    private static final int TIMEOUT_MS = 10000;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private Thread listenerThread;

    // Response queue: the listener thread puts responses here,
    // sendRequest() takes from here. Capacity 1 is enough — one request at a time.
    private final BlockingQueue<NetworkMessage> responseQueue = new LinkedBlockingQueue<>(10);

    private final List<Observer<NetworkMessage>> observers = new CopyOnWriteArrayList<>();
    private Consumer<Boolean> connectionStatusCallback;

    public ServerConnection() {}

    /**
     * Connects to the server at the given host and port.
     * Starts the single listener thread that handles all incoming messages.
     */
    public boolean connect(String host, int port) {
        try {
            socket = new Socket(host, port);
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in = new ObjectInputStream(socket.getInputStream());
            connected.set(true);

            startListenerThread();
            LOGGER.info("Connected to DistriCompute server at " + host + ":" + port);
            if (connectionStatusCallback != null) connectionStatusCallback.accept(true);
            return true;

        } catch (IOException e) {
            LOGGER.warning("Failed to connect to server: " + e.getMessage());
            connected.set(false);
            return false;
        }
    }

    /**
     * Sends a request to the server and waits for a response via the response queue.
     * The listener thread is the only one reading from the stream — no more race condition.
     */
    public NetworkMessage sendRequest(NetworkMessage request) {
// Wait for login to complete (max 3 seconds)
        int waited = 0;
        while (!ClientSession.getInstance().isLoggedIn() && waited < 3000) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            waited += 100;
        }
        if (!connected.get()) {
            return NetworkMessage.error(Type.ERROR, "Not connected to server.");
        }
        try {
            // Clear any stale response left in the queue
            responseQueue.clear();

            // Send the request
            synchronized (out) {
                out.writeObject(request);
                out.flush();
                out.reset();
            }

            // Wait for the listener thread to put the response in the queue
            NetworkMessage response = responseQueue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (response == null) {
                return NetworkMessage.error(Type.ERROR, "Request timed out.");
            }
            return response;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return NetworkMessage.error(Type.ERROR, "Request interrupted.");
        } catch (IOException e) {
            connected.set(false);
            if (connectionStatusCallback != null) connectionStatusCallback.accept(false);
            return NetworkMessage.error(Type.ERROR, "Connection lost: " + e.getMessage());
        }
    }

    /**
     * Single listener thread — reads every incoming message and routes it:
     * - Push messages (task updates, notifications) → observers
     * - Everything else (request responses) → responseQueue for sendRequest()
     */
    private void startListenerThread() {
        listenerThread = new Thread(() -> {
            while (connected.get() && !socket.isClosed()) {
                try {
                    Object obj = in.readObject();
                    if (!(obj instanceof NetworkMessage msg)) continue;

                    Type type = msg.getType();

                    // Server-pushed messages go to observers
                    if (type == Type.TASK_STATUS_UPDATE ||
                            type == Type.TASK_PROGRESS_UPDATE ||
                            type == Type.NOTIFICATION) {
                        notifyObservers(msg);
                    } else {
                        // Response to a sendRequest() call — put in queue
                        // offer with timeout to avoid blocking the listener forever
                        responseQueue.offer(msg, 5, TimeUnit.SECONDS);
                    }

                } catch (EOFException | java.net.SocketException e) {
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException | ClassNotFoundException e) {
                    if (connected.get()) {
                        LOGGER.warning("Listener error: " + e.getMessage());
                    }
                    break;
                }
            }
            connected.set(false);
            if (connectionStatusCallback != null) connectionStatusCallback.accept(false);
        }, "server-push-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    public void disconnect() {
        connected.set(false);
        try {
            if (socket != null && !socket.isClosed()) {
                synchronized (out) {
                    out.writeObject(NetworkMessage.of(Type.LOGOUT));
                    out.flush();
                }
                socket.close();
            }
        } catch (Exception ignored) {}
    }

    public boolean isConnected() {
        return connected.get() && socket != null && !socket.isClosed();
    }

    public void setConnectionStatusCallback(Consumer<Boolean> callback) {
        this.connectionStatusCallback = callback;
    }

    // -------------------------------------------------------------------------
    // Observer pattern
    // -------------------------------------------------------------------------

    @Override
    public void addObserver(Observer<NetworkMessage> observer) { observers.add(observer); }

    @Override
    public void removeObserver(Observer<NetworkMessage> observer) { observers.remove(observer); }

    @Override
    public void notifyObservers(NetworkMessage message) {
        for (Observer<NetworkMessage> obs : observers) {
            try { obs.onEvent(message); } catch (Exception ignored) {}
        }
    }
}