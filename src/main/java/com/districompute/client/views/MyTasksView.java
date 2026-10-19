package com.districompute.client.views;

import com.districompute.client.ClientSession;
import com.districompute.client.components.TaskProgressCard;
import com.districompute.client.components.ToastNotification;
import com.districompute.common.models.NetworkMessage;
import com.districompute.common.models.NetworkMessage.Type;
import com.districompute.network.ServerConnection;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * My Tasks view — shows tasks with filtering, sorting, batch grouping,
 * edit dialog, and admin delete.
 */
public class MyTasksView extends VBox {

    private final ServerConnection connection;
    private VBox taskList;
    private VBox queuePanel;
    private Label queueCountLabel;

    // Filters
    private ComboBox<String> statusFilter;
    private ComboBox<String> priorityFilter;
    private ComboBox<String> sortBy;
    private CheckBox showOnlyMine; // admin only

    // Data
    private final Map<String, TaskProgressCard> taskCards = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> taskData = new LinkedHashMap<>();
    private final Map<String, VBox> taskCardContainers = new LinkedHashMap<>();
    private List<Map<String, Object>> allTasksRaw = new ArrayList<>();

    // Batch tracking: batchId -> list of taskIds in that batch
    private final Map<String, List<String>> batchTaskIds = new LinkedHashMap<>();
    // Batch UI: batchId -> [summaryLabel, progressRow]
    private final Map<String, Label> batchSummaryLabels = new LinkedHashMap<>();
    private final Map<String, HBox> batchProgressRows = new LinkedHashMap<>();

    // Edit dialog reference (to auto-close)
    private Dialog<?> activeEditDialog;
    private String activeEditTaskId;

    public MyTasksView(ServerConnection connection) {
        this.connection = connection;
        buildUI();
        refresh();
    }

    private void buildUI() {
        getStyleClass().add("content-area");
        setSpacing(20);

        Label title = new Label("My Tasks");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Track your submitted computations in real time");
        subtitle.getStyleClass().add("page-subtitle");
        VBox header = new VBox(4, title, subtitle);

        // Queue panel
        queueCountLabel = new Label("Queue: loading...");
        queueCountLabel.getStyleClass().add("task-meta-label");
        queuePanel = new VBox(8);
        Label queueTitle = new Label("QUEUE STATUS");
        queueTitle.getStyleClass().add("card-title");
        VBox queueCard = new VBox(8, queueTitle, queueCountLabel, queuePanel);
        queueCard.getStyleClass().add("card");

        // Filter bar
        HBox filterBar = buildFilterBar();

        // Tasks list
        Label tasksTitle = new Label("ALL MY TASKS");
        tasksTitle.getStyleClass().add("card-title");
        taskList = new VBox(12);

        Button refreshBtn = new Button("Refresh");
        refreshBtn.getStyleClass().add("btn-secondary");
        refreshBtn.setOnAction(e -> refresh());
        HBox refreshRow = new HBox(refreshBtn);
        refreshRow.setAlignment(Pos.CENTER_RIGHT);

        VBox tasksCard = new VBox(10, tasksTitle, taskList);
        tasksCard.getStyleClass().add("card");

        getChildren().addAll(header, queueCard, filterBar, refreshRow, tasksCard);
    }

    private HBox buildFilterBar() {
        Label filterLabel = new Label("Filter:");
        filterLabel.getStyleClass().add("form-label");

        statusFilter = new ComboBox<>();
        statusFilter.getItems().addAll("All Statuses", "PENDING", "EXECUTING", "COMPLETED", "FAILED", "CANCELLED");
        statusFilter.setValue("All Statuses");
        statusFilter.setOnAction(e -> applyFilters());

        priorityFilter = new ComboBox<>();
        priorityFilter.getItems().addAll("All Priorities", "HIGH", "MEDIUM", "LOW");
        priorityFilter.setValue("All Priorities");
        priorityFilter.setOnAction(e -> applyFilters());

        sortBy = new ComboBox<>();
        sortBy.getItems().addAll("Newest First", "Oldest First", "Deadline (Earliest)", "Deadline (Latest)", "Priority (High→Low)", "Priority (Low→High)");
        sortBy.setValue("Newest First");
        sortBy.setOnAction(e -> applyFilters());

        HBox bar = new HBox(10, filterLabel, statusFilter, priorityFilter, new Label("Sort:"), sortBy);
        bar.setAlignment(Pos.CENTER_LEFT);

        if (ClientSession.getInstance().isAdmin()) {
            showOnlyMine = new CheckBox("Show only my tasks");
            showOnlyMine.setSelected(false);
            showOnlyMine.getStyleClass().add("task-meta-label");
            showOnlyMine.setOnAction(e -> refresh());
            bar.getChildren().add(showOnlyMine);
        }

        return bar;
    }

    public void refresh() {
        Thread t = new Thread(() -> {
            boolean isAdmin = ClientSession.getInstance().isAdmin();
            boolean showAll = isAdmin && (showOnlyMine == null || !showOnlyMine.isSelected());

            NetworkMessage tasksResp;
            if (showAll && isAdmin) {
                tasksResp = connection.sendRequest(NetworkMessage.of(Type.GET_ALL_TASKS));
            } else {
                tasksResp = connection.sendRequest(NetworkMessage.of(Type.GET_MY_TASKS));
            }
            NetworkMessage queueResp = connection.sendRequest(NetworkMessage.of(Type.GET_QUEUE));

            Platform.runLater(() -> {
                if (tasksResp.isSuccess() && tasksResp.getPayload() instanceof List<?> list) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> tasks = (List<Map<String, Object>>) list;
                    allTasksRaw = tasks;
                    applyFilters();
                } else {
                    taskList.getChildren().clear();
                    Label err = new Label("Could not load tasks. Please refresh.");
                    err.getStyleClass().add("task-meta-label");
                    err.setPadding(new Insets(20));
                    taskList.getChildren().add(err);
                }
                if (queueResp.isSuccess() && queueResp.getPayload() instanceof List<?> list) {
                    updateQueue(list);
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private void applyFilters() {
        String statusVal = statusFilter.getValue();
        String priorityVal = priorityFilter.getValue();
        String sortVal = sortBy.getValue();

        List<Map<String, Object>> filtered = new ArrayList<>(allTasksRaw);

        // Filter by status
        if (statusVal != null && !statusVal.equals("All Statuses")) {
            // Map filter values to actual statuses
            if (statusVal.equals("EXECUTING")) {
                filtered = filtered.stream().filter(t -> {
                    String s = (String) t.getOrDefault("status", "");
                    return s.equals("EXECUTING") || s.equals("RECEIVED") || s.equals("VALIDATING") ||
                           s.equals("DISTRIBUTING") || s.equals("MERGING") || s.equals("FINALIZING");
                }).collect(Collectors.toList());
            } else {
                filtered = filtered.stream()
                        .filter(t -> statusVal.equals(t.get("status")))
                        .collect(Collectors.toList());
            }
        }

        // Filter by priority
        if (priorityVal != null && !priorityVal.equals("All Priorities")) {
            filtered = filtered.stream()
                    .filter(t -> priorityVal.equals(t.get("priority")))
                    .collect(Collectors.toList());
        }

        // Sort
        if (sortVal != null) {
            switch (sortVal) {
                case "Newest First" -> filtered.sort((a, b) -> {
                    String sa = (String) a.getOrDefault("submittedAt", "");
                    String sb = (String) b.getOrDefault("submittedAt", "");
                    return sb.compareTo(sa);
                });
                case "Oldest First" -> filtered.sort((a, b) -> {
                    String sa = (String) a.getOrDefault("submittedAt", "");
                    String sb = (String) b.getOrDefault("submittedAt", "");
                    return sa.compareTo(sb);
                });
                case "Deadline (Earliest)" -> filtered.sort((a, b) -> {
                    String da = (String) a.get("deadline");
                    String db = (String) b.get("deadline");
                    if (da == null && db == null) return 0;
                    if (da == null) return 1;
                    if (db == null) return -1;
                    return da.compareTo(db);
                });
                case "Deadline (Latest)" -> filtered.sort((a, b) -> {
                    String da = (String) a.get("deadline");
                    String db = (String) b.get("deadline");
                    if (da == null && db == null) return 0;
                    if (da == null) return 1;
                    if (db == null) return -1;
                    return db.compareTo(da);
                });
                case "Priority (High→Low)" -> filtered.sort((a, b) -> {
                    int pa = priorityOrder((String) a.getOrDefault("priority", "MEDIUM"));
                    int pb = priorityOrder((String) b.getOrDefault("priority", "MEDIUM"));
                    return Integer.compare(pa, pb);
                });
                case "Priority (Low→High)" -> filtered.sort((a, b) -> {
                    int pa = priorityOrder((String) a.getOrDefault("priority", "MEDIUM"));
                    int pb = priorityOrder((String) b.getOrDefault("priority", "MEDIUM"));
                    return Integer.compare(pb, pa);
                });
            }
        }

        updateTaskList(filtered);
    }

    private int priorityOrder(String p) {
        return switch (p) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            case "LOW" -> 2;
            default -> 3;
        };
    }

    @SuppressWarnings("unchecked")
    private void updateTaskList(List<Map<String, Object>> list) {
        taskList.getChildren().clear();
        taskCards.clear();
        taskData.clear();
        taskCardContainers.clear();
        batchTaskIds.clear();
        batchSummaryLabels.clear();
        batchProgressRows.clear();

        if (list.isEmpty()) {
            Label empty = new Label("No tasks match the current filters.");
            empty.getStyleClass().add("task-meta-label");
            empty.setPadding(new Insets(20));
            taskList.getChildren().add(empty);
            return;
        }

        // Group by batchId
        Map<String, List<Map<String, Object>>> batches = new LinkedHashMap<>();
        List<Map<String, Object>> singles = new ArrayList<>();

        for (Map<String, Object> task : list) {
            String batchId = (String) task.get("batchId");
            if (batchId != null && !batchId.isBlank()) {
                batches.computeIfAbsent(batchId, k -> new ArrayList<>()).add(task);
            } else {
                singles.add(task);
            }
        }

        // Render batch groups
        for (Map.Entry<String, List<Map<String, Object>>> entry : batches.entrySet()) {
            String batchId = entry.getKey();
            List<Map<String, Object>> batchTasks = entry.getValue();
            VBox batchContainer = buildBatchGroup(batchId, batchTasks);
            taskList.getChildren().add(batchContainer);
        }

        // Render individual tasks
        for (Map<String, Object> task : singles) {
            String taskId = (String) task.get("id");
            taskData.put(taskId, task);
            VBox container = buildFullTaskCard(task);
            taskCardContainers.put(taskId, container);
            taskList.getChildren().add(container);
        }
    }

    private VBox buildBatchGroup(String batchId, List<Map<String, Object>> tasks) {
        // Track which tasks belong to this batch
        List<String> taskIds = new ArrayList<>();
        for (Map<String, Object> task : tasks) {
            taskIds.add((String) task.get("id"));
        }
        batchTaskIds.put(batchId, taskIds);

        // Summary
        long completed = tasks.stream().filter(t -> "COMPLETED".equals(t.get("status"))).count();
        long failed = tasks.stream().filter(t -> "FAILED".equals(t.get("status"))).count();
        long total = tasks.size();
        long done = completed + failed;

        Label batchLabel = new Label("Batch — " + total + " Tasks");
        batchLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: -dc-text-primary; -fx-font-size: 13px;");

        Label summaryLabel = new Label(done + "/" + total + " Done  •  " +
                completed + " Successful  •  " + failed + " Failed");
        summaryLabel.getStyleClass().add("task-meta-label");
        batchSummaryLabels.put(batchId, summaryLabel);

        // Expand/collapse toggle
        VBox batchContent = new VBox(12);
        batchContent.setVisible(false);
        batchContent.setManaged(false);

        Button toggleBtn = new Button("▸ Show Details");
        toggleBtn.getStyleClass().add("btn-secondary");
        toggleBtn.setStyle("-fx-font-size: 11px;");
        toggleBtn.setOnAction(e -> {
            boolean showing = batchContent.isVisible();
            batchContent.setVisible(!showing);
            batchContent.setManaged(!showing);
            toggleBtn.setText(showing ? "▸ Show Details" : "▾ Hide Details");
        });

        // Batch progress animation
        HBox progressRow = buildBatchProgressRow(tasks);
        batchProgressRows.put(batchId, progressRow);

        // Build individual cards inside batch
        for (Map<String, Object> task : tasks) {
            String taskId = (String) task.get("id");
            taskData.put(taskId, task);
            VBox container = buildFullTaskCard(task);
            taskCardContainers.put(taskId, container);
            batchContent.getChildren().add(container);
        }

        VBox batchGroup = new VBox(8, batchLabel, summaryLabel, progressRow, toggleBtn, batchContent);
        batchGroup.getStyleClass().add("card");
        batchGroup.setStyle("-fx-border-color: -dc-accent; -fx-border-width: 1; -fx-border-radius: 10; " +
                "-fx-background-color: -dc-bg-card; -fx-background-radius: 10; -fx-padding: 16;");
        return batchGroup;
    }

    private HBox buildBatchProgressRow(List<Map<String, Object>> tasks) {
        HBox progressRow = new HBox(4);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < tasks.size(); i++) {
            Map<String, Object> task = tasks.get(i);
            String status = (String) task.getOrDefault("status", "PENDING");
            String taskTitle = (String) task.getOrDefault("title", "Task " + (i + 1));

            String color = switch (status) {
                case "COMPLETED" -> "#2dd69b";
                case "FAILED" -> "#f5564a";
                case "CANCELLED" -> "#8b90a0";
                case "PENDING" -> "#555b70";
                default -> "#4f8ef7";
            };
            String icon = switch (status) {
                case "COMPLETED" -> "✓ ";
                case "FAILED" -> "✗ ";
                case "CANCELLED" -> "— ";
                default -> "";
            };
            Label stepLabel = new Label(icon + taskTitle);
            stepLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + color + ";");
            progressRow.getChildren().add(stepLabel);

            if (i < tasks.size() - 1) {
                Label arrow = new Label(" → ");
                arrow.setStyle("-fx-text-fill: #555b70; -fx-font-size: 11px;");
                progressRow.getChildren().add(arrow);
            }
        }
        return progressRow;
    }

    /**
     * Updates the batch summary and progress row when a task in the batch gets a status update.
     */
    private void updateBatchSummary(String batchId) {
        List<String> taskIds = batchTaskIds.get(batchId);
        if (taskIds == null) return;

        // Gather current data for all tasks in this batch
        List<Map<String, Object>> batchTasks = new ArrayList<>();
        for (String tid : taskIds) {
            Map<String, Object> td = taskData.get(tid);
            if (td != null) batchTasks.add(td);
        }

        long completed = batchTasks.stream().filter(t -> "COMPLETED".equals(t.get("status"))).count();
        long failed = batchTasks.stream().filter(t -> "FAILED".equals(t.get("status"))).count();
        long total = batchTasks.size();
        long done = completed + failed;

        // Update summary label
        Label summaryLabel = batchSummaryLabels.get(batchId);
        if (summaryLabel != null) {
            summaryLabel.setText(done + "/" + total + " Done  •  " +
                    completed + " Successful  •  " + failed + " Failed");
        }

        // Rebuild progress row
        HBox progressRow = batchProgressRows.get(batchId);
        if (progressRow != null) {
            HBox newRow = buildBatchProgressRow(batchTasks);
            progressRow.getChildren().setAll(newRow.getChildren());
        }
    }

    private VBox buildFullTaskCard(Map<String, Object> task) {
        String taskId = (String) task.get("id");
        String status = (String) task.getOrDefault("status", "PENDING");
        String execTime = (String) task.getOrDefault("formattedExecutionTime", "");
        String resultText = (String) task.get("resultText");
        String jarFile = (String) task.getOrDefault("jarFileName", "task.jar");
        String title = (String) task.getOrDefault("title", "Untitled");
        String assignedTo = (String) task.get("assignedToUsername");
        String deadline = (String) task.get("deadline");
        boolean editable = Boolean.TRUE.equals(task.get("editable"));

        TaskProgressCard progressCard = new TaskProgressCard();
        progressCard.updateFromMap(task);
        taskCards.put(taskId, progressCard);

        HBox actions = buildActionButtons(task);

        // Meta info
        StringBuilder metaStr = new StringBuilder("JAR: " + jarFile);
        if (execTime != null && !execTime.isBlank()) metaStr.append(" • Time: ").append(execTime);
        if (assignedTo != null && !assignedTo.isBlank()) metaStr.append(" • Assigned to: ").append(assignedTo);
        if (deadline != null && !deadline.isBlank()) metaStr.append(" • Deadline: ").append(deadline);
        Label meta = new Label(metaStr.toString());
        meta.getStyleClass().add("task-meta-label");
        meta.setId("meta-" + taskId);

        VBox card = new VBox(10, progressCard, actions, meta);
        card.getStyleClass().add("card");
        card.setUserData(taskId);
        return card;
    }

    private HBox buildActionButtons(Map<String, Object> task) {
        String taskId = (String) task.get("id");
        String status = (String) task.getOrDefault("status", "PENDING");
        String resultText = (String) task.get("resultText");
        String title = (String) task.getOrDefault("title", "Untitled");
        boolean editable = Boolean.TRUE.equals(task.get("editable"));
        boolean isAdmin = ClientSession.getInstance().isAdmin();

        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setId("actions-" + taskId);

        // Cancel button
        if (status.equals("PENDING") || status.equals("RECEIVED")) {
            Button cancelBtn = new Button("Cancel");
            cancelBtn.getStyleClass().add("btn-danger");
            cancelBtn.setOnAction(e -> handleCancel(taskId, cancelBtn));
            actions.getChildren().add(cancelBtn);
        }

        // Edit button (only PENDING)
        if (editable) {
            Button editBtn = new Button("Edit");
            editBtn.getStyleClass().add("btn-secondary");
            editBtn.setOnAction(e -> showEditDialog(task));
            actions.getChildren().add(editBtn);
        }

        // View result button
        if (status.equals("COMPLETED") && resultText != null && !resultText.isBlank()) {
            Button viewResult = new Button("View Result");
            FontIcon eye = new FontIcon("fas-eye");
            eye.setIconSize(13);
            eye.setIconColor(javafx.scene.paint.Color.web("#8b90a0"));
            viewResult.setGraphic(eye);
            viewResult.getStyleClass().add("btn-secondary");
            viewResult.setOnAction(e -> showResultDialog(title, resultText));
            actions.getChildren().add(viewResult);
        }

        // Delete button (admin only)
        if (isAdmin) {
            Button deleteBtn = new Button("Delete");
            deleteBtn.getStyleClass().add("btn-danger");
            deleteBtn.setStyle("-fx-font-size: 11px;");
            deleteBtn.setOnAction(e -> handleDelete(taskId, title));
            actions.getChildren().add(deleteBtn);
        }

        return actions;
    }

    // -------------------------------------------------------------------------
    // Edit dialog
    // -------------------------------------------------------------------------

    private void showEditDialog(Map<String, Object> task) {
        String taskId = (String) task.get("id");

        Dialog<Map<String, Object>> dialog = new Dialog<>();
        dialog.setTitle("Edit Task — " + task.getOrDefault("title", "Untitled"));
        dialog.setHeaderText(null);
        activeEditDialog = dialog;
        activeEditTaskId = taskId;

        TextField titleField = new TextField((String) task.getOrDefault("title", ""));
        titleField.setMaxWidth(400);

        TextArea descField = new TextArea((String) task.getOrDefault("description", ""));
        descField.setPrefRowCount(3);
        descField.setMaxWidth(400);

        DatePicker deadlinePicker = new DatePicker();
        String deadlineStr = (String) task.get("deadline");
        if (deadlineStr != null && !deadlineStr.isBlank()) {
            try { deadlinePicker.setValue(java.time.LocalDate.parse(deadlineStr)); } catch (Exception ignored) {}
        }
        deadlinePicker.setMaxWidth(400);

        // Assigned To
        ComboBox<String> assignedCombo = new ComboBox<>();
        assignedCombo.setMaxWidth(400);
        // Fetch user list
        Thread userThread = new Thread(() -> {
            NetworkMessage resp = connection.sendRequest(NetworkMessage.of(Type.GET_USER_LIST));
            if (resp.isSuccess() && resp.getPayload() instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> users = (List<Map<String, String>>) list;
                Platform.runLater(() -> {
                    String currentAssigned = (String) task.get("assignedToUsername");
                    for (Map<String, String> u : users) {
                        String uname = u.get("username");
                        assignedCombo.getItems().add(uname);
                        if (uname.equals(currentAssigned)) {
                            assignedCombo.setValue(uname);
                        }
                    }
                });
            }
        });
        userThread.setDaemon(true);
        userThread.start();

        VBox content = new VBox(12,
                new Label("Title"), titleField,
                new Label("Description"), descField,
                new Label("Deadline"), deadlinePicker,
                new Label("Assigned To"), assignedCombo
        );
        content.setPadding(new Insets(16));
        content.setPrefWidth(440);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (getScene() != null) {
            dialog.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
        }

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                Map<String, Object> edits = new LinkedHashMap<>();
                edits.put("taskId", taskId);
                edits.put("title", titleField.getText());
                edits.put("description", descField.getText());
                edits.put("deadline", deadlinePicker.getValue() != null ? deadlinePicker.getValue().toString() : "");

                String selectedUser = assignedCombo.getValue();
                if (selectedUser != null) {
                    // Need to resolve username to userId — send as-is, server handles it
                    edits.put("assignedToUsername", selectedUser);
                    // Try to find userId
                    Thread resolveThread = new Thread(() -> {
                        NetworkMessage resp = connection.sendRequest(NetworkMessage.of(Type.GET_USER_LIST));
                        if (resp.isSuccess() && resp.getPayload() instanceof List<?> list) {
                            for (Object o : list) {
                                @SuppressWarnings("unchecked")
                                Map<String, String> u = (Map<String, String>) o;
                                if (selectedUser.equals(u.get("username"))) {
                                    edits.put("assignedToUserId", u.get("id"));
                                    break;
                                }
                            }
                        }
                    });
                    resolveThread.setDaemon(true);
                    resolveThread.start();
                    try { resolveThread.join(2000); } catch (InterruptedException ignored) {}
                }

                return edits;
            }
            return null;
        });

        Optional<Map<String, Object>> result = dialog.showAndWait();
        activeEditDialog = null;
        activeEditTaskId = null;

        result.ifPresent(edits -> {
            Thread t = new Thread(() -> {
                NetworkMessage resp = connection.sendRequest(new NetworkMessage(Type.EDIT_TASK, edits));
                Platform.runLater(() -> {
                    if (resp.isSuccess()) {
                        ToastNotification.show("Task updated!", ToastNotification.ToastType.SUCCESS);
                        refresh();
                    } else {
                        ToastNotification.show("Edit failed: " + resp.getMessage(), ToastNotification.ToastType.ERROR);
                    }
                });
            });
            t.setDaemon(true);
            t.start();
        });
    }

    // -------------------------------------------------------------------------
    // Queue
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void updateQueue(List<?> list) {
        queuePanel.getChildren().clear();
        String userId = ClientSession.getInstance().getUserId();

        List<Map<String, Object>> myQueueTasks = new ArrayList<>();
        for (Object obj : list) {
            Map<String, Object> t = (Map<String, Object>) obj;
            if (userId.equals(t.get("submittedByUserId"))) {
                myQueueTasks.add(t);
            }
        }

        if (myQueueTasks.isEmpty()) {
            queueCountLabel.setText("No tasks in queue.");
        } else {
            queueCountLabel.setText("Your tasks in queue: " + myQueueTasks.size() +
                    " (Global queue: " + list.size() + " total)");
            for (Map<String, Object> t : myQueueTasks) {
                Object pos = t.get("queuePosition");
                String title = (String) t.getOrDefault("title", "Untitled");
                Label row = new Label("#" + pos + " — " + title);
                row.getStyleClass().add("task-meta-label");
                queuePanel.getChildren().add(row);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Real-time updates
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public void handleTaskUpdate(Map<String, Object> updatedTask) {
        String taskId = (String) updatedTask.get("id");

        Platform.runLater(() -> {
            taskData.put(taskId, updatedTask);

            // Auto-close edit dialog if this task is no longer editable
            if (taskId.equals(activeEditTaskId)) {
                boolean editable = Boolean.TRUE.equals(updatedTask.get("editable"));
                if (!editable && activeEditDialog != null) {
                    activeEditDialog.close();
                    activeEditDialog = null;
                    activeEditTaskId = null;
                    ToastNotification.show("Task is now being processed — editing closed.",
                            ToastNotification.ToastType.INFO);
                }
            }

            // Update progress card
            TaskProgressCard card = taskCards.get(taskId);
            if (card != null) {
                card.updateFromMap(updatedTask);
            }

            // Rebuild action buttons
            VBox container = taskCardContainers.get(taskId);
            if (container != null) {
                HBox newActions = buildActionButtons(updatedTask);
                if (container.getChildren().size() >= 2) {
                    container.getChildren().set(1, newActions);
                }

                // Update meta
                String execTime = (String) updatedTask.getOrDefault("formattedExecutionTime", "");
                String jarFile = (String) updatedTask.getOrDefault("jarFileName", "task.jar");
                String assignedTo = (String) updatedTask.get("assignedToUsername");
                String deadline = (String) updatedTask.get("deadline");
                if (container.getChildren().size() >= 3 &&
                        container.getChildren().get(2) instanceof Label metaLabel) {
                    StringBuilder metaStr = new StringBuilder("JAR: " + jarFile);
                    if (execTime != null && !execTime.isBlank()) metaStr.append(" • Time: ").append(execTime);
                    if (assignedTo != null && !assignedTo.isBlank()) metaStr.append(" • Assigned to: ").append(assignedTo);
                    if (deadline != null && !deadline.isBlank()) metaStr.append(" • Deadline: ").append(deadline);
                    metaLabel.setText(metaStr.toString());
                }
            } else {
                refresh();
            }

            // Update batch summary if this task belongs to a batch
            String batchId = (String) updatedTask.get("batchId");
            if (batchId != null && !batchId.isBlank()) {
                updateBatchSummary(batchId);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void handleCancel(String taskId, Button cancelBtn) {
        cancelBtn.setDisable(true);
        cancelBtn.setText("Cancelling...");

        Map<String, String> payload = Map.of("taskId", taskId);
        Thread t = new Thread(() -> {
            NetworkMessage resp = connection.sendRequest(new NetworkMessage(Type.CANCEL_TASK, payload));
            Platform.runLater(() -> {
                if (resp.isSuccess()) {
                    ToastNotification.show("Task cancelled.", ToastNotification.ToastType.INFO);
                    refresh();
                } else {
                    cancelBtn.setDisable(false);
                    cancelBtn.setText("Cancel");
                    ToastNotification.show("Could not cancel: " + resp.getMessage(),
                            ToastNotification.ToastType.ERROR);
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private void handleDelete(String taskId, String taskTitle) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Task");
        confirm.setHeaderText("Delete '" + taskTitle + "'?");
        confirm.setContentText("This action cannot be undone.");
        if (getScene() != null) {
            confirm.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
        }

        confirm.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                Map<String, String> payload = Map.of("taskId", taskId);
                Thread t = new Thread(() -> {
                    NetworkMessage resp = connection.sendRequest(new NetworkMessage(Type.DELETE_TASK, payload));
                    Platform.runLater(() -> {
                        if (resp.isSuccess()) {
                            ToastNotification.show("Task deleted.", ToastNotification.ToastType.INFO);
                            refresh();
                        } else {
                            ToastNotification.show("Could not delete: " + resp.getMessage(),
                                    ToastNotification.ToastType.ERROR);
                        }
                    });
                });
                t.setDaemon(true);
                t.start();
            }
        });
    }

    private void showResultDialog(String taskTitle, String resultText) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Task Result — " + taskTitle);
        dialog.setHeaderText(null);

        TextArea textArea = new TextArea(resultText);
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefSize(600, 400);
        textArea.getStyleClass().add("console-area");

        Button downloadBtn = new Button("Download .txt");
        downloadBtn.getStyleClass().add("btn-secondary");
        downloadBtn.setOnAction(e -> downloadResult(taskTitle, resultText));

        VBox content = new VBox(12, new Label("Result output:"), textArea, downloadBtn);
        content.setPadding(new Insets(16));
        content.setPrefWidth(640);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        if (getScene() != null) {
            dialog.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
        }

        dialog.showAndWait();
    }

    private void downloadResult(String taskTitle, String resultText) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Result");
        chooser.setInitialFileName(
                taskTitle.replaceAll("[^a-zA-Z0-9_\\-]", "_") + "_result.txt");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Text files", "*.txt"));

        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file != null) {
            try (FileWriter fw = new FileWriter(file)) {
                fw.write("DistriCompute Task Result\n");
                fw.write("Task: " + taskTitle + "\n");
                fw.write("Generated: " + java.time.LocalDateTime.now() + "\n");
                fw.write("----------------------------------------\n\n");
                fw.write(resultText);
                ToastNotification.show("Result saved successfully!",
                        ToastNotification.ToastType.SUCCESS);
            } catch (IOException ex) {
                ToastNotification.show("Failed to save: " + ex.getMessage(),
                        ToastNotification.ToastType.ERROR);
            }
        }
    }
}
