package com.districompute.server;

import com.districompute.common.models.*;
import com.districompute.common.models.NetworkMessage.Type;
import com.districompute.common.models.Task.Status;
import com.districompute.common.models.User.Role;
import com.districompute.common.patterns.Observer;
import com.districompute.common.utils.AppLogger;
import com.districompute.common.utils.ServerConfig;
import com.districompute.dao.TaskDAO;
import com.districompute.dao.UserDAO;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.mindrot.jbcrypt.BCrypt;

import java.io.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.Socket;

/**
 * Handles a single connected client in its own thread.
 * Implements Observer&lt;Task&gt; to push task updates to this specific client.
 */
public class ClientHandler implements Runnable, Observer<Task> {

    private final Socket socket;
    private final UserDAO userDAO;
    private final TaskDAO taskDAO;
    private final TaskExecutor taskExecutor;
    private final AppLogger logger = AppLogger.getInstance();
    private final MasterServer server;

    private ObjectInputStream in;
    private ObjectOutputStream out;
    private boolean streamsProvided = false;

    private User currentUser;
    private final AtomicBoolean connected = new AtomicBoolean(true);
    private final String clientId;

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class,
                    (com.google.gson.JsonSerializer<LocalDateTime>) (src, type, ctx) ->
                            new com.google.gson.JsonPrimitive(src.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
            .registerTypeAdapter(LocalDateTime.class,
                    (com.google.gson.JsonDeserializer<LocalDateTime>) (json, type, ctx) ->
                            LocalDateTime.parse(json.getAsString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            .create();

    public ClientHandler(Socket socket, UserDAO userDAO, TaskDAO taskDAO,
                         TaskExecutor taskExecutor, MasterServer server) {
        this.socket = socket;
        this.userDAO = userDAO;
        this.taskDAO = taskDAO;
        this.taskExecutor = taskExecutor;
        this.server = server;
        this.clientId = UUID.randomUUID().toString().substring(0, 8);
    }

    /** Constructor for unified port mode where streams are already created. */
    public ClientHandler(Socket socket, UserDAO userDAO, TaskDAO taskDAO,
                         TaskExecutor taskExecutor, MasterServer server,
                         ObjectOutputStream out, ObjectInputStream in) {
        this(socket, userDAO, taskDAO, taskExecutor, server);
        this.out = out;
        this.in = in;
        this.streamsProvided = true;
    }

    @Override
    public void run() {
        try {
            if (!streamsProvided) {
                out = new ObjectOutputStream(socket.getOutputStream());
                out.flush();
                in = new ObjectInputStream(socket.getInputStream());
            }

            logger.system("CLIENT_CONNECTED", "Client " + clientId + " connected from " +
                    socket.getInetAddress().getHostAddress());

            taskExecutor.addObserver(this);

            while (connected.get() && !socket.isClosed()) {
                NetworkMessage request = (NetworkMessage) in.readObject();
                if (request == null) break;
                NetworkMessage response = handleRequest(request);
                if (response != null) sendMessage(response);
            }

        } catch (EOFException | java.net.SocketException e) {
            // Client disconnected normally
        } catch (Exception e) {
            logger.warning("CLIENT_ERROR", "Error with client " + clientId + ": " + e.getMessage(),
                    currentUser != null ? currentUser.getId() : "UNKNOWN",
                    currentUser != null ? currentUser.getUsername() : "UNKNOWN");
        } finally {
            disconnect();
        }
    }

    /**
     * Called in unified port mode — the first message was already read by the router.
     */
    public void runWithFirstMessage(NetworkMessage firstMessage) {
        try {
            logger.system("CLIENT_CONNECTED", "Client " + clientId + " connected from " +
                    socket.getInetAddress().getHostAddress());

            taskExecutor.addObserver(this);

            // Handle the already-read first message
            NetworkMessage response = handleRequest(firstMessage);
            if (response != null) sendMessage(response);

            // Continue normal loop
            while (connected.get() && !socket.isClosed()) {
                NetworkMessage request = (NetworkMessage) in.readObject();
                if (request == null) break;
                response = handleRequest(request);
                if (response != null) sendMessage(response);
            }

        } catch (EOFException | java.net.SocketException e) {
            // Client disconnected normally
        } catch (Exception e) {
            logger.warning("CLIENT_ERROR", "Error with client " + clientId + ": " + e.getMessage(),
                    currentUser != null ? currentUser.getId() : "UNKNOWN",
                    currentUser != null ? currentUser.getUsername() : "UNKNOWN");
        } finally {
            disconnect();
        }
    }

    private NetworkMessage handleRequest(NetworkMessage req) {
        return switch (req.getType()) {
            case LOGIN_REQUEST -> handleLogin(req);
            case REGISTER_REQUEST -> handleRegister(req);
            case LOGOUT -> handleLogout(req);
            case SUBMIT_TASK -> handleSubmitTask(req);
            case SUBMIT_BATCH -> handleSubmitBatch(req);
            case CANCEL_TASK -> handleCancelTask(req);
            case EDIT_TASK -> handleEditTask(req);
            case DELETE_TASK -> handleDeleteTask(req);
            case GET_MY_TASKS -> handleGetMyTasks(req);
            case GET_ALL_TASKS -> handleGetAllTasks(req);
            case GET_QUEUE -> handleGetQueue(req);
            case GET_ALL_USERS -> handleGetAllUsers(req);
            case GET_USER_LIST -> handleGetUserList(req);
            case GET_LOGS -> handleGetLogs(req);
            case GET_STATS -> handleGetStats(req);
            case GET_SLAVES -> handleGetSlaves(req);
            case GET_SLAVE_LOGS -> handleGetSlaveLogs(req);
            case PING -> NetworkMessage.success(Type.PONG, null);
            default -> NetworkMessage.error(Type.ERROR, "Unknown request type: " + req.getType());
        };
    }

    // -------------------------------------------------------------------------
    // Auth handlers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private NetworkMessage handleLogin(NetworkMessage req) {
        Map<String, String> creds = (Map<String, String>) req.getPayload();
        if (creds == null) return NetworkMessage.error(Type.LOGIN_RESPONSE, "Invalid login data.");

        String username = creds.get("username");
        String password = creds.get("password");

        Optional<User> userOpt = userDAO.findByUsername(username);
        if (userOpt.isEmpty()) {
            logger.warning("LOGIN_FAILED", "Unknown user: " + username, "UNKNOWN", username);
            return NetworkMessage.error(Type.LOGIN_RESPONSE, "Invalid username or password.");
        }

        User user = userOpt.get();
        if (!BCrypt.checkpw(password, user.getPasswordHash())) {
            logger.warning("LOGIN_FAILED", "Wrong password for user: " + username, user.getId(), username);
            return NetworkMessage.error(Type.LOGIN_RESPONSE, "Invalid username or password.");
        }

        if (!user.isActive()) {
            return NetworkMessage.error(Type.LOGIN_RESPONSE, "Account is disabled.");
        }

        this.currentUser = user;
        logger.success("LOGIN", "User logged in.", user.getId(), username);

        Map<String, Object> userInfo = userToMap(user);
        return NetworkMessage.success(Type.LOGIN_RESPONSE, userInfo);
    }

    @SuppressWarnings("unchecked")
    private NetworkMessage handleRegister(NetworkMessage req) {
        Map<String, String> data = (Map<String, String>) req.getPayload();
        if (data == null) return NetworkMessage.error(Type.REGISTER_RESPONSE, "Invalid registration data.");

        String username = data.get("username");
        String password = data.get("password");
        String email = data.get("email");

        if (username == null || username.length() < 3) {
            return NetworkMessage.error(Type.REGISTER_RESPONSE, "Username must be at least 3 characters.");
        }
        if (password == null || password.length() < 6) {
            return NetworkMessage.error(Type.REGISTER_RESPONSE, "Password must be at least 6 characters.");
        }
        if (userDAO.existsByUsername(username)) {
            return NetworkMessage.error(Type.REGISTER_RESPONSE, "Username already taken.");
        }

        String hash = BCrypt.hashpw(password, BCrypt.gensalt());
        boolean isFirstUser = userDAO.findAll().isEmpty();

        User user = new User.Builder()
                .id(UUID.randomUUID().toString())
                .username(username)
                .passwordHash(hash)
                .email(email != null ? email : "")
                .role(isFirstUser ? Role.ADMIN : Role.STANDARD)
                .active(true)
                .build();

        userDAO.save(user);
        logger.success("REGISTER", "New user registered: " + username +
                        (isFirstUser ? " (granted ADMIN - first user)" : ""),
                user.getId(), username);

        this.currentUser = user;
        return NetworkMessage.success(Type.REGISTER_RESPONSE, userToMap(user));
    }

    private NetworkMessage handleLogout(NetworkMessage req) {
        if (currentUser != null) {
            logger.info("LOGOUT", "User logged out.", currentUser.getId(), currentUser.getUsername());
            currentUser = null;
        }
        connected.set(false);
        return null;
    }

    // -------------------------------------------------------------------------
    // Task handlers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private NetworkMessage handleSubmitTask(NetworkMessage req) {
        if (currentUser == null) return NetworkMessage.error(Type.SUBMIT_TASK_RESPONSE, "Not authenticated.");

        Map<String, Object> data = (Map<String, Object>) req.getPayload();
        if (data == null) return NetworkMessage.error(Type.SUBMIT_TASK_RESPONSE, "No task data.");

        Task task = buildTaskFromPayload(data);
        if (task == null) return NetworkMessage.error(Type.SUBMIT_TASK_RESPONSE, "Task title is required.");

        String error = validateAndSubmitTask(task, data);
        if (error != null) return NetworkMessage.error(Type.SUBMIT_TASK_RESPONSE, error);

        return NetworkMessage.success(Type.SUBMIT_TASK_RESPONSE, taskToMap(task));
    }

    @SuppressWarnings("unchecked")
    private NetworkMessage handleSubmitBatch(NetworkMessage req) {
        if (currentUser == null) return NetworkMessage.error(Type.SUBMIT_BATCH_RESPONSE, "Not authenticated.");

        List<Map<String, Object>> taskDataList = (List<Map<String, Object>>) req.getPayload();
        if (taskDataList == null || taskDataList.isEmpty()) {
            return NetworkMessage.error(Type.SUBMIT_BATCH_RESPONSE, "No tasks in batch.");
        }

        String batchId = UUID.randomUUID().toString();
        List<Map<String, Object>> submittedTasks = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < taskDataList.size(); i++) {
            Map<String, Object> data = taskDataList.get(i);
            Task task = buildTaskFromPayload(data);
            if (task == null) {
                errors.add("Task " + (i + 1) + ": title is required.");
                continue;
            }
            task.setBatchId(batchId);

            String error = validateAndSubmitTask(task, data);
            if (error != null) {
                errors.add("Task " + (i + 1) + " (" + task.getTitle() + "): " + error);
            } else {
                submittedTasks.add(taskToMap(task));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);
        result.put("submitted", submittedTasks);
        result.put("errors", errors);
        result.put("totalSubmitted", submittedTasks.size());
        result.put("totalFailed", errors.size());

        logger.info("BATCH_SUBMITTED", "Batch " + batchId + ": " + submittedTasks.size() +
                " submitted, " + errors.size() + " failed.",
                currentUser.getId(), currentUser.getUsername());

        return NetworkMessage.success(Type.SUBMIT_BATCH_RESPONSE, result);
    }

    @SuppressWarnings("unchecked")
    private Task buildTaskFromPayload(Map<String, Object> data) {
        String title = (String) data.get("title");
        if (title == null || title.isBlank()) return null;

        String description = (String) data.getOrDefault("description", "");
        String priorityStr = (String) data.getOrDefault("priority", "MEDIUM");
        String jarFileName = (String) data.get("jarFileName");
        String jarBase64 = (String) data.get("jarData");
        String splitterFileName = (String) data.get("splitterFileName");
        String splitterBase64 = (String) data.get("splitterData");
        String assignedToUserId = (String) data.get("assignedToUserId");
        String assignedToUsername = (String) data.get("assignedToUsername");
        String deadlineStr = (String) data.get("deadline");
        int requestedSlaves = 1;
        Object slavesObj = data.get("requestedSlaves");
        if (slavesObj instanceof Number n) requestedSlaves = n.intValue();

        Task.Priority priority;
        try { priority = Task.Priority.valueOf(priorityStr.toUpperCase()); }
        catch (IllegalArgumentException e) { priority = Task.Priority.MEDIUM; }

        LocalDate deadline = null;
        if (deadlineStr != null && !deadlineStr.isBlank()) {
            try { deadline = LocalDate.parse(deadlineStr); } catch (Exception ignored) {}
        }

        // Default assignedTo to current user
        if (assignedToUserId == null || assignedToUserId.isBlank()) {
            assignedToUserId = currentUser.getId();
            assignedToUsername = currentUser.getUsername();
        }

        byte[] jarData = null;
        byte[] splitterData = null;
        if (jarBase64 != null && !jarBase64.isBlank()) jarData = Base64.getDecoder().decode(jarBase64);
        if (splitterBase64 != null && !splitterBase64.isBlank()) splitterData = Base64.getDecoder().decode(splitterBase64);

        Task task = new Task.Builder()
                .id(UUID.randomUUID().toString())
                .title(title)
                .description(description)
                .priority(priority)
                .submittedByUserId(currentUser.getId())
                .submittedByUsername(currentUser.getUsername())
                .assignedToUserId(assignedToUserId)
                .assignedToUsername(assignedToUsername)
                .deadline(deadline)
                .jarFileName(jarFileName != null ? jarFileName : "task.jar")
                .jarData(jarData)
                .splitterFileName(splitterFileName != null ? splitterFileName : "splitter.jar")
                .splitterData(splitterData)
                .requestedSlaves(requestedSlaves)
                .status(Status.PENDING)
                .build();

        return task;
    }

    private String validateAndSubmitTask(Task task, Map<String, Object> data) {
        if (task.getJarData() == null || task.getJarData().length == 0) return "No Task JAR file provided.";
        if (task.getSplitterData() == null || task.getSplitterData().length == 0) return "No Splitter JAR file provided.";

        SlaveManager sm = SlaveManager.getInstance();
        int onlineSlaves = sm.getOnlineSlaveCount();
        int requestedSlaves = task.getRequestedSlaves();
        if (onlineSlaves == 0)
            return "No Slave nodes are currently online. Please try again when at least one Slave is connected.";
        if (requestedSlaves > onlineSlaves)
            return "You requested " + requestedSlaves + " Slave(s) but only " + onlineSlaves +
                    " are currently online.";
        if (requestedSlaves > ServerConfig.SLAVE_NAMES.length)
            return "Maximum " + ServerConfig.SLAVE_NAMES.length + " Slaves are available in this system.";

        String submitError = taskExecutor.submitTask(task);
        return submitError;
    }

    @SuppressWarnings("unchecked")
    private NetworkMessage handleEditTask(NetworkMessage req) {
        if (currentUser == null) return NetworkMessage.error(Type.EDIT_TASK_RESPONSE, "Not authenticated.");

        Map<String, Object> data = (Map<String, Object>) req.getPayload();
        if (data == null) return NetworkMessage.error(Type.EDIT_TASK_RESPONSE, "No edit data.");

        String taskId = (String) data.get("taskId");
        if (taskId == null) return NetworkMessage.error(Type.EDIT_TASK_RESPONSE, "No task ID.");

        Optional<Task> taskOpt = taskDAO.findById(taskId);
        if (taskOpt.isEmpty()) return NetworkMessage.error(Type.EDIT_TASK_RESPONSE, "Task not found.");

        Task task = taskOpt.get();

        // Only editable while PENDING
        if (!task.isEditable()) {
            return NetworkMessage.error(Type.EDIT_TASK_RESPONSE,
                    "Task is no longer editable (status: " + task.getStatus() + ").");
        }

        // Only owner or admin can edit
        if (!task.getSubmittedByUserId().equals(currentUser.getId()) && !currentUser.isAdmin()) {
            return NetworkMessage.error(Type.EDIT_TASK_RESPONSE, "Permission denied.");
        }

        // Apply edits
        String newTitle = (String) data.get("title");
        String newDescription = (String) data.get("description");
        String newDeadline = (String) data.get("deadline");
        String newAssignedToUserId = (String) data.get("assignedToUserId");
        String newAssignedToUsername = (String) data.get("assignedToUsername");

        if (newTitle != null && !newTitle.isBlank()) task.setTitle(newTitle);
        if (newDescription != null) task.setDescription(newDescription);
        if (newDeadline != null) {
            if (newDeadline.isBlank()) {
                task.setDeadline(null);
            } else {
                try { task.setDeadline(LocalDate.parse(newDeadline)); } catch (Exception ignored) {}
            }
        }
        if (newAssignedToUserId != null) {
            task.setAssignedToUserId(newAssignedToUserId);
            task.setAssignedToUsername(newAssignedToUsername);
        }

        taskDAO.update(task);
        logger.info("TASK_EDITED", "Task '" + task.getTitle() + "' edited.",
                currentUser.getId(), currentUser.getUsername());

        taskExecutor.notifyObservers(task);
        return NetworkMessage.success(Type.EDIT_TASK_RESPONSE, taskToMap(task));
    }

    @SuppressWarnings("unchecked")
    private NetworkMessage handleDeleteTask(NetworkMessage req) {
        if (currentUser == null) return NetworkMessage.error(Type.DELETE_TASK_RESPONSE, "Not authenticated.");
        if (!currentUser.isAdmin()) {
            return NetworkMessage.error(Type.DELETE_TASK_RESPONSE, "Admin access required.");
        }

        Map<String, String> data = (Map<String, String>) req.getPayload();
        String taskId = data != null ? data.get("taskId") : null;
        if (taskId == null) return NetworkMessage.error(Type.DELETE_TASK_RESPONSE, "No task ID.");

        Optional<Task> taskOpt = taskDAO.findById(taskId);
        if (taskOpt.isEmpty()) return NetworkMessage.error(Type.DELETE_TASK_RESPONSE, "Task not found.");

        Task task = taskOpt.get();

        // Cancel if still in queue
        if (task.isCancellable()) {
            taskExecutor.cancelTask(taskId, currentUser.getId());
        }

        taskDAO.delete(taskId);
        logger.info("TASK_DELETED", "Task '" + task.getTitle() + "' deleted by admin.",
                currentUser.getId(), currentUser.getUsername());

        return NetworkMessage.success(Type.DELETE_TASK_RESPONSE, taskId);
    }

    private NetworkMessage handleCancelTask(NetworkMessage req) {
        if (currentUser == null) return NetworkMessage.error(Type.CANCEL_TASK_RESPONSE, "Not authenticated.");

        Map<String, String> data = (Map<String, String>) req.getPayload();
        String taskId = data != null ? data.get("taskId") : null;
        if (taskId == null) return NetworkMessage.error(Type.CANCEL_TASK_RESPONSE, "No task ID provided.");

        Optional<Task> taskOpt = taskDAO.findById(taskId);
        if (taskOpt.isEmpty()) return NetworkMessage.error(Type.CANCEL_TASK_RESPONSE, "Task not found.");

        Task task = taskOpt.get();
        if (!task.getSubmittedByUserId().equals(currentUser.getId()) && !currentUser.isAdmin()) {
            return NetworkMessage.error(Type.CANCEL_TASK_RESPONSE, "Permission denied.");
        }

        boolean cancelled = taskExecutor.cancelTask(taskId, currentUser.getId());
        if (!cancelled) {
            return NetworkMessage.error(Type.CANCEL_TASK_RESPONSE,
                    "Task cannot be cancelled (may already be executing or completed).");
        }

        return NetworkMessage.success(Type.CANCEL_TASK_RESPONSE, taskId);
    }

    private NetworkMessage handleGetMyTasks(NetworkMessage req) {
        if (currentUser == null) return NetworkMessage.error(Type.GET_MY_TASKS_RESPONSE, "Not authenticated.");
        List<Task> tasks = taskDAO.findByUserId(currentUser.getId());
        return NetworkMessage.success(Type.GET_MY_TASKS_RESPONSE, tasksToMapList(tasks));
    }

    private NetworkMessage handleGetAllTasks(NetworkMessage req) {
        if (currentUser == null || !currentUser.isAdmin()) {
            return NetworkMessage.error(Type.GET_ALL_TASKS_RESPONSE, "Admin access required.");
        }
        return NetworkMessage.success(Type.GET_ALL_TASKS_RESPONSE, tasksToMapList(taskDAO.findAll()));
    }

    private NetworkMessage handleGetQueue(NetworkMessage req) {
        if (currentUser == null) return NetworkMessage.error(Type.GET_QUEUE_RESPONSE, "Not authenticated.");
        return NetworkMessage.success(Type.GET_QUEUE_RESPONSE, tasksToMapList(taskExecutor.getQueueSnapshot()));
    }

    // -------------------------------------------------------------------------
    // User handlers
    // -------------------------------------------------------------------------

    private NetworkMessage handleGetAllUsers(NetworkMessage req) {
        if (currentUser == null || !currentUser.isAdmin()) {
            return NetworkMessage.error(Type.GET_ALL_USERS_RESPONSE, "Admin access required.");
        }
        Set<String> onlineUsers = new HashSet<>();
        for (ClientHandler client : server.getConnectedClients()) {
            if (client.getCurrentUser() != null) {
                onlineUsers.add(client.getCurrentUser().getUsername());
            }
        }
        List<Map<String, Object>> users = new ArrayList<>();
        for (User u : userDAO.findAll()) {
            Map<String, Object> m = userToMap(u);
            m.put("online", onlineUsers.contains(u.getUsername()));
            users.add(m);
        }
        return NetworkMessage.success(Type.GET_ALL_USERS_RESPONSE, users);
    }

    /**
     * Returns a simplified user list for the assignment dropdown.
     * Available to all authenticated users.
     */
    private NetworkMessage handleGetUserList(NetworkMessage req) {
        if (currentUser == null) return NetworkMessage.error(Type.GET_USER_LIST_RESPONSE, "Not authenticated.");
        List<Map<String, String>> users = new ArrayList<>();
        for (User u : userDAO.findAll()) {
            if (!u.isActive()) continue;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("role", u.getRole().name());
            users.add(m);
        }
        return NetworkMessage.success(Type.GET_USER_LIST_RESPONSE, users);
    }

    private NetworkMessage handleGetLogs(NetworkMessage req) {
        if (currentUser == null || !currentUser.isAdmin()) {
            return NetworkMessage.error(Type.GET_LOGS_RESPONSE, "Admin access required.");
        }
        List<LogEntry> logs = logger.getLiveBuffer();
        return NetworkMessage.success(Type.GET_LOGS_RESPONSE, logs);
    }

    private NetworkMessage handleGetStats(NetworkMessage req) {
        if (currentUser == null) return NetworkMessage.error(Type.GET_STATS_RESPONSE, "Not authenticated.");

        List<Task> allTasks;
        if (currentUser.isAdmin()) {
            allTasks = taskDAO.findAll();
        } else {
            allTasks = taskDAO.findByUserId(currentUser.getId());
        }

        ServerStats stats = new ServerStats();
        stats.setTotalTasks(allTasks.size());
        stats.setCompletedTasks((int) allTasks.stream().filter(t -> t.getStatus() == Status.COMPLETED).count());
        stats.setFailedTasks((int) allTasks.stream().filter(t -> t.getStatus() == Status.FAILED).count());
        stats.setPendingTasks((int) allTasks.stream()
                .filter(t -> t.getStatus() == Status.PENDING || t.getStatus() == Status.EXECUTING).count());
        stats.setConnectedClients(server.getConnectedClientCount());
        stats.setTotalUsers(userDAO.findAll().size());
        stats.setOnlineSlaves(SlaveManager.getInstance().getOnlineSlaveCount());

        return NetworkMessage.success(Type.GET_STATS_RESPONSE, stats);
    }

    // -------------------------------------------------------------------------
    // Slave handlers
    // -------------------------------------------------------------------------

    private NetworkMessage handleGetSlaves(NetworkMessage req) {
        if (currentUser == null) return NetworkMessage.error(Type.GET_SLAVES_RESPONSE, "Not authenticated.");
        if (currentUser.getRole() != Role.ADMIN)
            return NetworkMessage.error(Type.GET_SLAVES_RESPONSE, "Admin access required.");
        List<SlaveNode> slaves = SlaveManager.getInstance().getAllSlaves();
        List<Map<String, Object>> result = new ArrayList<>();
        for (SlaveNode s : slaves) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name",            s.getName());
            m.put("port",            s.getPort());
            m.put("host",            s.getHost() != null ? s.getHost() : "--");
            m.put("status",          s.getStatus() != null ? s.getStatus().name() : "OFFLINE");
            m.put("javaVersion",     s.getJavaVersion() != null ? s.getJavaVersion() : "--");
            m.put("osName",          s.getOsName() != null ? s.getOsName() : "--");
            m.put("currentTaskTitle",s.getCurrentTaskTitle());
            m.put("activeTasks",     s.getActiveTasks());
            m.put("completedTasks",  s.getCompletedTasks());
            m.put("failedTasks",     s.getFailedTasks());
            m.put("uptimeMs",        s.getUptimeMs());
            m.put("recentLoadHistory", s.getRecentLoadHistory() != null ? s.getRecentLoadHistory() : List.of());
            result.add(m);
        }
        return NetworkMessage.success(Type.GET_SLAVES_RESPONSE, result);
    }

    private NetworkMessage handleGetSlaveLogs(NetworkMessage req) {
        if (currentUser == null) return NetworkMessage.error(Type.GET_SLAVE_LOGS_RESPONSE, "Not authenticated.");
        if (currentUser.getRole() != Role.ADMIN)
            return NetworkMessage.error(Type.GET_SLAVE_LOGS_RESPONSE, "Admin access required.");
        String slaveName = req.getPayload() instanceof String s ? s : null;
        if (slaveName == null) return NetworkMessage.error(Type.GET_SLAVE_LOGS_RESPONSE, "Slave name required.");
        List<String> logs = SlaveManager.getInstance().getSlaveLog(slaveName);
        return NetworkMessage.success(Type.GET_SLAVE_LOGS_RESPONSE, logs);
    }

    // -------------------------------------------------------------------------
    // Observer: push task updates to this client
    // -------------------------------------------------------------------------

    @Override
    public void onEvent(Task task) {
        if (currentUser == null) return;
        if (task.getSubmittedByUserId().equals(currentUser.getId()) ||
            (task.getAssignedToUserId() != null && task.getAssignedToUserId().equals(currentUser.getId())) ||
            currentUser.isAdmin()) {
            NetworkMessage update = NetworkMessage.success(Type.TASK_STATUS_UPDATE, taskToMap(task));
            sendMessage(update);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public synchronized void sendMessage(NetworkMessage message) {
        if (!connected.get() || socket.isClosed()) return;
        try {
            out.writeObject(message);
            out.flush();
            out.reset();
        } catch (IOException e) {
            connected.set(false);
        }
    }

    private void disconnect() {
        connected.set(false);
        taskExecutor.removeObserver(this);
        server.removeClient(this);
        try { socket.close(); } catch (IOException ignored) {}

        logger.system("CLIENT_DISCONNECTED", "Client " + clientId +
                (currentUser != null ? " (" + currentUser.getUsername() + ")" : "") + " disconnected.");
    }

    private Map<String, Object> userToMap(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("email", u.getEmail());
        m.put("role", u.getRole().name());
        m.put("active", u.isActive());
        m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> taskToMap(Task t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("title", t.getTitle());
        m.put("description", t.getDescription());
        m.put("priority", t.getPriority().name());
        m.put("status", t.getStatus().name());
        m.put("submittedByUserId", t.getSubmittedByUserId());
        m.put("submittedByUsername", t.getSubmittedByUsername());
        m.put("assignedToUserId", t.getAssignedToUserId());
        m.put("assignedToUsername", t.getAssignedToUsername());
        m.put("deadline", t.getDeadline() != null ? t.getDeadline().toString() : null);
        m.put("batchId", t.getBatchId());
        m.put("jarFileName", t.getJarFileName());
        m.put("splitterFileName", t.getSplitterFileName());
        m.put("requestedSlaves", t.getRequestedSlaves());
        m.put("assignedSlaves", t.getAssignedSlaves());
        m.put("submittedAt", t.getSubmittedAt() != null ? t.getSubmittedAt().toString() : null);
        m.put("startedAt", t.getStartedAt() != null ? t.getStartedAt().toString() : null);
        m.put("completedAt", t.getCompletedAt() != null ? t.getCompletedAt().toString() : null);
        m.put("executionTimeMs", t.getExecutionTimeMs());
        m.put("formattedExecutionTime", t.getFormattedExecutionTime());
        m.put("resultText", t.getResultText());
        m.put("errorMessage", t.getErrorMessage());
        m.put("queuePosition", t.getQueuePosition());
        m.put("editable", t.isEditable());
        return m;
    }

    private List<Map<String, Object>> tasksToMapList(List<Task> tasks) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Task t : tasks) result.add(taskToMap(t));
        return result;
    }

    public boolean isConnected() { return connected.get(); }
    public User getCurrentUser() { return currentUser; }
    public String getClientId() { return clientId; }
}
