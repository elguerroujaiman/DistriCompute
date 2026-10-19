package com.districompute.client.views;

import com.districompute.common.models.LogEntry;
import com.districompute.common.models.NetworkMessage;
import com.districompute.common.models.NetworkMessage.Type;
import com.districompute.network.ServerConnection;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;
import java.util.Map;

/**
 * Server Console view — tabbed: Master + Mercury + Venus + Mars.
 * Admin only. Shows live server and per-Slave activity logs.
 */
public class LogsView extends VBox {

    private final ServerConnection connection;

    private TextArea masterArea;
    private TextArea mercuryArea;
    private TextArea venusArea;
    private TextArea marsArea;

    private Label masterCountLabel;
    private Label mercuryCountLabel;
    private Label venusCountLabel;
    private Label marsCountLabel;

    private Tab mercuryTab, venusTab, marsTab;

    public LogsView(ServerConnection connection) {
        this.connection = connection;
        buildUI();
        refresh();
    }

    private void buildUI() {
        getStyleClass().add("content-area");
        setSpacing(20);
        VBox.setVgrow(this, Priority.ALWAYS);

        Label title = new Label("Server Console");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Live activity logs — Master server and all Slave nodes");
        subtitle.getStyleClass().add("page-subtitle");
        VBox header = new VBox(4, title, subtitle);

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getStyleClass().add("console-tabs");
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        // Master tab
        masterCountLabel = new Label("");
        masterCountLabel.getStyleClass().add("task-meta-label");
        masterArea = createConsoleArea();
        tabPane.getTabs().add(buildTab("Master", "fas-server", "#6366f1",
                masterArea, masterCountLabel, () -> refreshMaster()));

        // Slave tabs
        mercuryCountLabel = new Label("");
        mercuryArea = createConsoleArea();
        mercuryTab = buildTab("Mercury", "fas-circle", "#94a3b8",
                mercuryArea, mercuryCountLabel, () -> refreshSlave("Mercury", mercuryArea, mercuryCountLabel));
        tabPane.getTabs().add(mercuryTab);

        venusCountLabel = new Label("");
        venusArea = createConsoleArea();
        venusTab = buildTab("Venus", "fas-globe", "#fbbf24",
                venusArea, venusCountLabel, () -> refreshSlave("Venus", venusArea, venusCountLabel));
        tabPane.getTabs().add(venusTab);

        marsCountLabel = new Label("");
        marsArea = createConsoleArea();
        marsTab = buildTab("Mars", "fas-meteor", "#f87171",
                marsArea, marsCountLabel, () -> refreshSlave("Mars", marsArea, marsCountLabel));
        tabPane.getTabs().add(marsTab);

        getChildren().addAll(header, tabPane);
    }

    private Tab buildTab(String name, String iconCode, String iconColor,
                          TextArea area, Label countLabel, Runnable refreshAction) {
        Tab tab = new Tab();

        // Tab header with icon
        FontIcon icon = new FontIcon(iconCode);
        icon.setIconColor(Color.web(iconColor));
        icon.setIconSize(13);
        Label tabLabel = new Label(name);
        tabLabel.setStyle("-fx-font-size: 12px;");
        tabLabel.getStyleClass().add("nav-item-label");
        HBox tabHeader = new HBox(5, icon, tabLabel);
        tabHeader.setAlignment(Pos.CENTER_LEFT);
        tab.setGraphic(tabHeader);

        // Buttons
        FontIcon refreshIcon = new FontIcon("fas-sync-alt");
        refreshIcon.setIconColor(Color.web("#8b94b0"));
        refreshIcon.setIconSize(13);
        Button refreshBtn = new Button("Refresh Logs", refreshIcon);
        refreshBtn.getStyleClass().add("btn-secondary");
        refreshBtn.setOnAction(e -> refreshAction.run());

        FontIcon clearIcon = new FontIcon("fas-trash");
        clearIcon.setIconColor(Color.web("#8b94b0"));
        clearIcon.setIconSize(13);
        Button clearBtn = new Button("Clear View", clearIcon);
        clearBtn.getStyleClass().add("btn-secondary");
        clearBtn.setOnAction(e -> area.clear());

        HBox buttonRow = new HBox(8, refreshBtn, clearBtn, countLabel);
        buttonRow.setAlignment(Pos.CENTER_LEFT);
        buttonRow.setPadding(new Insets(8, 0, 4, 0));

        VBox content = new VBox(6, buttonRow, area);
        content.getStyleClass().add("card");
        content.setPadding(new Insets(12));
        VBox.setVgrow(area, Priority.ALWAYS);
        VBox.setVgrow(content, Priority.ALWAYS);

        tab.setContent(content);
        return tab;
    }

    private TextArea createConsoleArea() {
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("console-area");
        area.setPrefHeight(460);
        area.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(area, Priority.ALWAYS);
        return area;
    }

    public void refresh() {
        refreshMaster();
        refreshSlave("Mercury", mercuryArea, mercuryCountLabel);
        refreshSlave("Venus", venusArea, venusCountLabel);
        refreshSlave("Mars", marsArea, marsCountLabel);
    }

    private void refreshMaster() {
        Thread t = new Thread(() -> {
            NetworkMessage resp = connection.sendRequest(NetworkMessage.of(Type.GET_LOGS));
            Platform.runLater(() -> {
                if (resp != null && resp.isSuccess() && resp.getPayload() instanceof List<?> list) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = list.size() - 1; i >= 0; i--) {
                        Object entry = list.get(i);
                        if (entry instanceof LogEntry log) {
                            sb.append(formatLogEntry(log.getLevel().name(),
                                    log.getTimestamp().toString(), log.getUsername(),
                                    log.getAction(), log.getDetails()));
                        } else if (entry instanceof Map<?, ?> m) {
                            sb.append(formatLogEntry(
                                    str(m, "level", "INFO"), str(m, "timestamp", ""),
                                    str(m, "username", "SYSTEM"), str(m, "action", ""),
                                    str(m, "details", "")));
                        }
                    }
                    masterArea.setText(sb.toString());
                    masterArea.positionCaret(0);
                    masterCountLabel.setText(list.size() + " log entries");
                } else {
                    masterArea.setText("Failed to load Master logs.");
                    masterCountLabel.setText("Error");
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private void refreshSlave(String slaveName, TextArea area, Label countLabel) {
        Thread t = new Thread(() -> {
            NetworkMessage req = new NetworkMessage(Type.GET_SLAVE_LOGS, slaveName);
            NetworkMessage resp = connection.sendRequest(req);
            Platform.runLater(() -> {
                if (resp != null && resp.isSuccess() && resp.getPayload() instanceof List<?> list) {
                    if (list.isEmpty()) {
                        area.setText("No logs yet for " + slaveName + ".\n" +
                                "Start the " + slaveName + " Slave node to see activity here.");
                        countLabel.setText("0 entries · Offline or no activity");
                    } else {
                        StringBuilder sb = new StringBuilder();
                        for (int i = list.size() - 1; i >= 0; i--) {
                            sb.append(list.get(i)).append("\n");
                        }
                        area.setText(sb.toString());
                        area.positionCaret(0);
                        countLabel.setText(list.size() + " log entries");
                    }
                } else {
                    area.setText(slaveName + " is offline or no logs available.");
                    countLabel.setText("● Offline");
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    public void appendLiveLog(Map<String, Object> entry) {
        Platform.runLater(() -> {
            String level = String.valueOf(entry.getOrDefault("level", "INFO"));
            String ts = String.valueOf(entry.getOrDefault("timestamp", ""));
            String action = String.valueOf(entry.getOrDefault("action", ""));
            String details = String.valueOf(entry.getOrDefault("details", ""));
            String user = String.valueOf(entry.getOrDefault("username", "SYSTEM"));
            String line = formatLogEntry(level, ts, user, action, details);
            masterArea.insertText(0, line);
        });
    }

    private String formatLogEntry(String level, String ts, String user, String action, String details) {
        String prefix = switch (level.toUpperCase()) {
            case "SUCCESS" -> "✓";
            case "ERROR" -> "✗";
            case "WARNING" -> "⚠";
            default -> "•";
        };
        return String.format("%s [%s] [%s] %s: %s%n",
                prefix, formatTimestamp(ts), user, action, details);
    }

    private String formatTimestamp(String ts) {
        if (ts == null || ts.isBlank() || ts.equals("null")) return "--:--:--";
        try {
            java.time.LocalDateTime dt = java.time.LocalDateTime.parse(ts);
            return dt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        } catch (Exception e) {
            return ts.length() > 19 ? ts.substring(11, 19) : ts;
        }
    }

    private String str(Map<?, ?> m, String key, String def) {
        Object v = m.get(key);
        return v != null && !v.toString().equals("null") ? v.toString() : def;
    }
}
