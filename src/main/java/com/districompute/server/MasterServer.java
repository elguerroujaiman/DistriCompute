package com.districompute.server;

import com.districompute.common.models.NetworkMessage;
import com.districompute.common.models.NetworkMessage.Type;
import com.districompute.common.models.SlaveNode;
import com.districompute.common.utils.AppLogger;
import com.districompute.common.utils.ServerConfig;
import com.districompute.dao.*;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;


public class MasterServer {

    private final AppLogger logger = AppLogger.getInstance();
    private final ServerConfig config = ServerConfig.getInstance();
    private final SlaveManager slaveManager = SlaveManager.getInstance();

    private ServerSocket clientServerSocket;
    private ServerSocket slaveServerSocket;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService clientThreadPool;
    private ExecutorService slaveThreadPool;

    private UserDAO userDAO;
    private TaskDAO taskDAO;
    private LogDAO logDAO;
    private TaskExecutor taskExecutor;

    private final List<ClientHandler> connectedClients = new CopyOnWriteArrayList<>();

    public MasterServer() {}

    public void init() {
        DAOFactory daoFactory = new DAOFactory(DAOFactory.StorageType.JSON, config.getDataDirectory());
        this.userDAO = daoFactory.createUserDAO();
        this.taskDAO = daoFactory.createTaskDAO();
        this.logDAO = daoFactory.createLogDAO();
        logger.setLogDAO(logDAO);

        this.taskExecutor = TaskExecutor.getInstance();
        taskExecutor.init(new DistributedExecutionStrategy(slaveManager), taskDAO);

        logger.system("SERVER_INIT",
                "V2 Server initialized. Mode=" + (config.isUnifiedPort() ? "UNIFIED" : "DUAL") +
                " ClientPort=" + config.getPort() +
                (config.isUnifiedPort() ? "" : ", SlavePort=" + config.getSlavePort()));
    }

    public void start() throws IOException {
        init();
        taskExecutor.start();

        clientThreadPool = Executors.newCachedThreadPool();
        slaveThreadPool = Executors.newCachedThreadPool();

        clientServerSocket = new ServerSocket(config.getPort());
        running.set(true);

        if (config.isUnifiedPort()) {
            // Single port mode — both clients and slaves connect here
            logger.system("SERVER_START", "DistriCompute Master Server V2 started (UNIFIED PORT).");
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.println("║   DistriCompute Server  - V2             ║");
            System.out.println("║   Unified port : " + config.getPort() + "                    ║");
            System.out.println("║   Mode: UNIFIED (ngrok-friendly)         ║");
            System.out.println("║   Mercury=9092  Venus=9093  Mars=9094    ║");
            System.out.println("╚══════════════════════════════════════════╝");
            unifiedAcceptLoop();
        } else {
            // Dual port mode (original)
            slaveServerSocket = new ServerSocket(config.getSlavePort());
            logger.system("SERVER_START", "DistriCompute Master Server V2 started (DUAL PORT).");
            System.out.println("╔══════════════════════════════════════╗");
            System.out.println("║   DistriCompute Server  - V2         ║");
            System.out.println("║   Client port : " + config.getPort() + "                 ║");
            System.out.println("║   Slave  port : " + config.getSlavePort() + "                 ║");
            System.out.println("║   Mercury=9092  Venus=9093  Mars=9094║");
            System.out.println("╚══════════════════════════════════════╝");

            Thread slaveAcceptThread = new Thread(this::slaveAcceptLoop, "slave-accept");
            slaveAcceptThread.setDaemon(true);
            slaveAcceptThread.start();

            clientAcceptLoop();
        }
    }

    /**
     * Unified accept loop — reads the first message to determine if
     * the connection is from a client or a slave, then routes accordingly.
     */
    private void unifiedAcceptLoop() {
        while (running.get() && !clientServerSocket.isClosed()) {
            try {
                Socket s = clientServerSocket.accept();
                clientThreadPool.submit(() -> routeConnection(s));
            } catch (IOException e) {
                if (running.get())
                    logger.error("ACCEPT_ERROR", e.getMessage(), "SYSTEM", "SYSTEM");
            }
        }
    }

    private void routeConnection(Socket socket) {
        try {
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

            // Read the first message to determine connection type
            Object firstObj = in.readObject();
            if (!(firstObj instanceof NetworkMessage firstMsg)) {
                socket.close();
                return;
            }

            if (firstMsg.getType() == Type.SLAVE_REGISTER) {
                // This is a slave connection — handle slave handshake
                handleSlaveHandshakeWithStreams(socket, firstMsg, out, in);
            } else {
                // This is a client connection — create handler and replay first message
                ClientHandler h = new ClientHandler(socket, userDAO, taskDAO, taskExecutor, this, out, in);
                connectedClients.add(h);
                clientThreadPool.submit(() -> h.runWithFirstMessage(firstMsg));
            }
        } catch (Exception e) {
            logger.error("ROUTE_ERROR", "Failed to route connection: " + e.getMessage(), "SYSTEM", "SYSTEM");
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void clientAcceptLoop() {
        while (running.get() && !clientServerSocket.isClosed()) {
            try {
                Socket s = clientServerSocket.accept();
                ClientHandler h = new ClientHandler(s, userDAO, taskDAO, taskExecutor, this);
                connectedClients.add(h);
                clientThreadPool.submit(h);
            } catch (IOException e) {
                if (running.get())
                    logger.error("CLIENT_ACCEPT_ERROR", e.getMessage(), "SYSTEM", "SYSTEM");
            }
        }
    }

    private void slaveAcceptLoop() {
        while (running.get() && !slaveServerSocket.isClosed()) {
            try {
                Socket s = slaveServerSocket.accept();
                slaveThreadPool.submit(() -> handleSlaveHandshake(s));
            } catch (IOException e) {
                if (running.get())
                    logger.error("SLAVE_ACCEPT_ERROR", e.getMessage(), "SYSTEM", "SYSTEM");
            }
        }
    }

    private void handleSlaveHandshake(Socket slaveSocket) {
        try {
            ObjectOutputStream out = new ObjectOutputStream(slaveSocket.getOutputStream());
            out.flush();
            ObjectInputStream in = new ObjectInputStream(slaveSocket.getInputStream());

            Object obj = in.readObject();
            if (!(obj instanceof NetworkMessage msg) || msg.getType() != Type.SLAVE_REGISTER) {
                slaveSocket.close();
                return;
            }

            handleSlaveHandshakeWithStreams(slaveSocket, msg, out, in);

        } catch (Exception e) {
            logger.error("SLAVE_HANDSHAKE_ERROR", e.getMessage(), "SYSTEM", "SYSTEM");
            try { slaveSocket.close(); } catch (IOException ignored) {}
        }
    }

    private void handleSlaveHandshakeWithStreams(Socket slaveSocket, NetworkMessage msg,
                                                  ObjectOutputStream out, ObjectInputStream in) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> reg = (Map<String, String>) msg.getPayload();
            int slavePort = Integer.parseInt(reg.getOrDefault("slavePort", "0"));
            String javaVer = reg.getOrDefault("javaVersion", "unknown");
            String osName = reg.getOrDefault("osName", "unknown");

            SlaveNode node = slaveManager.onSlaveConnected(slaveSocket, slavePort, javaVer, osName);
            if (node == null) {
                out.writeObject(NetworkMessage.error(Type.ERROR, "Unknown slave port: " + slavePort));
                slaveSocket.close();
                return;
            }

            SlaveConnection conn = new SlaveConnection(slaveSocket, node, slaveManager, out, in);
            slaveManager.registerConnection(node.getName(), conn);
            conn.run();

        } catch (Exception e) {
            logger.error("SLAVE_HANDSHAKE_ERROR", e.getMessage(), "SYSTEM", "SYSTEM");
            try { slaveSocket.close(); } catch (IOException ignored) {}
        }
    }

    public void stop() {
        running.set(false);
        taskExecutor.stop();
        clientThreadPool.shutdown();
        slaveThreadPool.shutdown();
        try { if (clientServerSocket != null) clientServerSocket.close(); } catch (IOException ignored) {}
        try { if (slaveServerSocket != null) slaveServerSocket.close(); } catch (IOException ignored) {}
        logger.system("SERVER_STOP", "Server stopped.");
    }

    public void removeClient(ClientHandler h) { connectedClients.remove(h); }
    public int getConnectedClientCount() {
        return (int) connectedClients.stream().filter(ClientHandler::isConnected).count();
    }
    public List<ClientHandler> getConnectedClients() { return connectedClients; }
    public UserDAO getUserDAO() { return userDAO; }
    public TaskDAO getTaskDAO() { return taskDAO; }
    public AppLogger getLogger() { return logger; }
    public SlaveManager getSlaveManager() { return slaveManager; }

    public static void main(String[] args) {
        MasterServer server = new MasterServer();

        // Parse command line args
        for (String arg : args) {
            if ("--unified".equalsIgnoreCase(arg)) {
                ServerConfig.getInstance().setUnifiedPort(true);
            }
        }

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        try { server.start(); }
        catch (IOException e) { System.err.println("Failed to start: " + e.getMessage()); System.exit(1); }
    }
}
