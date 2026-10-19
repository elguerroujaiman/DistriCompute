package com.districompute.client.views;

import com.districompute.client.ClientSession;
import com.districompute.client.controllers.MainController;
import com.districompute.common.models.NetworkMessage;
import com.districompute.common.models.NetworkMessage.Type;
import com.districompute.network.ServerConnection;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;
import java.util.Map;

import org.kordamp.ikonli.javafx.FontIcon;
import javafx.scene.layout.StackPane;

/**
 * Dashboard view — shows stats summary and recent tasks.
 */
public class DashboardView extends VBox {

    private final ServerConnection connection;
    private final MainController mainController;
    private final ClientSession session = ClientSession.getInstance();

    private Label totalLabel, completedLabel, failedLabel, pendingLabel, slavesLabel;
    private VBox recentTasksList;

    public DashboardView(ServerConnection connection, MainController mainController) {
        this.connection = connection;
        this.mainController = mainController;
        buildUI();
        refresh();
    }

    private void buildUI() {
        getStyleClass().add("content-area");
        setSpacing(24);

        Label title = new Label("Dashboard");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Welcome back, " + session.getUsername() + "!");
        subtitle.getStyleClass().add("page-subtitle");
        VBox header = new VBox(4, title, subtitle);

        HBox statsRow = buildStatsRow();

        Label recentTitle = new Label("RECENT TASKS");
        recentTitle.getStyleClass().add("card-title");

        recentTasksList = new VBox(10);

        VBox recentCard = new VBox(8, recentTitle, recentTasksList);
        recentCard.getStyleClass().add("card");

        Button refreshBtn = new Button("Refresh");
        refreshBtn.getStyleClass().add("btn-secondary");
        refreshBtn.setOnAction(e -> refresh());
        HBox refreshRow = new HBox(refreshBtn);
        refreshRow.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(header, statsRow, recentCard, refreshRow);
    }

    private HBox buildStatsRow() {
        totalLabel = new Label("0");
        completedLabel = new Label("0");
        failedLabel = new Label("0");
        pendingLabel = new Label("0");
        slavesLabel = new Label("0/3");

        VBox totalCard     = statCard("fas-layer-group",    "Total Tasks",   totalLabel,     "#4f8ef7");
        VBox completedCard = statCard("fas-check-circle",   "Completed",     completedLabel, "#2dd69b");
        VBox failedCard    = statCard("fas-times-circle",   "Failed",        failedLabel,    "#f5564a");
        VBox pendingCard   = statCard("fas-spinner",        "In Progress",   pendingLabel,   "#f5a623");
        VBox slavesCard    = statCard("fas-network-wired",  "Slaves Online", slavesLabel,    "#6366f1");

        HBox row = new HBox(16, totalCard, completedCard, failedCard, pendingCard, slavesCard);
        row.setAlignment(Pos.CENTER_LEFT);
        for (var card : List.of(totalCard, completedCard, failedCard, pendingCard, slavesCard)) {
            HBox.setHgrow(card, Priority.ALWAYS);
        }
        return row;
    }

    private VBox statCard(String iconCode, String labelText, Label valueLabel, String color) {
        FontIcon icon = new FontIcon(iconCode);
        icon.setIconSize(22);
        icon.setIconColor(javafx.scene.paint.Color.web("#4f8ef7"));

        Label lbl = new Label(labelText);
        lbl.getStyleClass().add("stat-label");

        valueLabel.setStyle(
                "-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");

        VBox card = new VBox(6, icon, valueLabel, lbl);
        card.getStyleClass().add("card");
        card.setAlignment(Pos.CENTER_LEFT);
        return card;
    }

    public void refresh() {
        Thread t = new Thread(() -> {
            NetworkMessage statsResp = connection.sendRequest(NetworkMessage.of(Type.GET_STATS));
            NetworkMessage tasksResp = connection.sendRequest(NetworkMessage.of(Type.GET_MY_TASKS));

            Platform.runLater(() -> {
                // --- Stats ---
                if (statsResp.isSuccess() && statsResp.getPayload() != null) {
                    Object payload = statsResp.getPayload();
                    if (payload instanceof com.districompute.common.models.ServerStats stats) {
                        totalLabel.setText(String.valueOf(stats.getTotalTasks()));
                        completedLabel.setText(String.valueOf(stats.getCompletedTasks()));
                        failedLabel.setText(String.valueOf(stats.getFailedTasks()));
                        pendingLabel.setText(String.valueOf(stats.getPendingTasks()));
                        slavesLabel.setText(stats.getOnlineSlaves() + "/3");
                    } else {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> stats = (Map<String, Object>) payload;
                            totalLabel.setText(extractInt(stats, "totalTasks"));
                            completedLabel.setText(extractInt(stats, "completedTasks"));
                            failedLabel.setText(extractInt(stats, "failedTasks"));
                            pendingLabel.setText(extractInt(stats, "pendingTasks"));
                            slavesLabel.setText(extractInt(stats, "onlineSlaves") + "/3");
                        } catch (Exception e) {
                            // leave at 0
                        }
                    }
                }

                // --- Recent tasks ---
                if (tasksResp.isSuccess() && tasksResp.getPayload() instanceof List<?> list) {
                    recentTasksList.getChildren().clear();
                    int count = Math.min(5, list.size());
                    if (count == 0) {
                        Label empty = new Label("No tasks yet. Submit your first task!");
                        empty.getStyleClass().add("task-meta-label");
                        empty.setPadding(new Insets(12));
                        recentTasksList.getChildren().add(empty);
                    } else {
                        for (int i = 0; i < count; i++) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> task = (Map<String, Object>) list.get(i);
                            recentTasksList.getChildren().add(buildTaskRow(task));
                        }
                        if (list.size() > 5) {
                            Label more = new Label(
                                    "+ " + (list.size() - 5) + " more tasks — see My Tasks");
                            more.getStyleClass().add("link-label");
                            more.setOnMouseClicked(e -> mainController.navigateTo("MY_TASKS"));
                            recentTasksList.getChildren().add(more);
                        }
                    }
                }
                if (!statsResp.isSuccess()) {
                    totalLabel.setText("—");
                    completedLabel.setText("—");
                    failedLabel.setText("—");
                    pendingLabel.setText("—");
                    slavesLabel.setText("—/3");
                }
                if (!tasksResp.isSuccess()) {
                    recentTasksList.getChildren().clear();
                    Label err = new Label("Could not load tasks. Please refresh.");
                    err.getStyleClass().add("task-meta-label");
                    err.setPadding(new Insets(12));
                    recentTasksList.getChildren().add(err);
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private HBox buildTaskRow(Map<String, Object> task) {
        String title    = (String) task.getOrDefault("title", "Untitled");
        String status   = (String) task.getOrDefault("status", "PENDING");
        String priority = (String) task.getOrDefault("priority", "MEDIUM");
        String execTime = (String) task.getOrDefault("formattedExecutionTime", "");
        Object slavesObj = task.get("assignedSlaves");
        String slavesStr = "";
        if (slavesObj instanceof List<?> sl && !sl.isEmpty()) {
            slavesStr = "🌐 " + String.join(", ", sl.stream().map(Object::toString).toList());
        }

        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("task-title-label");
        titleLbl.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(titleLbl, Priority.ALWAYS);

        Label statusBadge = new Label(status);
        statusBadge.getStyleClass().addAll("status-badge", "status-" + status.toLowerCase());

        Label priorityBadge = new Label(priority);
        priorityBadge.getStyleClass().addAll("priority-badge", "priority-" + priority.toLowerCase());

        HBox badgeRow = new HBox(6, priorityBadge, statusBadge);
        badgeRow.setAlignment(Pos.CENTER_RIGHT);

        VBox titleBox = new VBox(2, titleLbl);
        if (!slavesStr.isBlank()) {
            Label slavesLbl = new Label(slavesStr);
            slavesLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #6366f1;");
            titleBox.getChildren().add(slavesLbl);
        }
        if (execTime != null && !execTime.isBlank() && !"- ".equals(execTime)) {
            Label timeLbl = new Label("⏱ " + execTime);
            timeLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: -dc-text-secondary;");
            titleBox.getChildren().add(timeLbl);
        }
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        HBox row = new HBox(10, titleBox, badgeRow);
        row.getStyleClass().add("task-card");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(12, 16, 12, 16));
        row.setOnMouseClicked(e -> mainController.navigateTo("MY_TASKS"));
        return row;
    }

    /**
     * Safely extracts an integer value from a Map regardless of whether
     * Gson deserialised it as Double, Integer, Long, or String.
     */
    private String extractInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return "0";
        if (val instanceof Number n) return String.valueOf(n.intValue());
        try { return String.valueOf((int) Double.parseDouble(val.toString())); }
        catch (NumberFormatException e) { return "0"; }
    }
}