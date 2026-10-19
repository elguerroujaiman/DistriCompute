package com.districompute.client.views;

import com.districompute.common.models.NetworkMessage;
import com.districompute.common.models.NetworkMessage.Type;
import com.districompute.common.models.SlaveNode;
import com.districompute.network.ServerConnection;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;
import java.util.Map;

/**
 * Admin-only Slaves panel. Shows Mercury, Venus, and Mars status cards
 * with real-time info, workload history bars, and execution stats.
 */
public class SlavesView extends VBox {

    private final ServerConnection connection;
    private final VBox slavesGrid;
    private Label onlineCountLabel;

    public SlavesView(ServerConnection connection) {
        this.connection = connection;
        getStyleClass().add("content-area");
        setSpacing(24);

        // Header
        Label title = new Label("Slave Nodes");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Real-time status of Mercury, Venus, and Mars compute nodes");
        subtitle.getStyleClass().add("page-subtitle");
        VBox header = new VBox(4, title, subtitle);

        // Stats bar
        onlineCountLabel = new Label("");
        onlineCountLabel.getStyleClass().add("task-meta-label");

        FontIcon refreshIcon = new FontIcon("fas-sync-alt");
        refreshIcon.setIconColor(Color.web("#8b94b0"));
        refreshIcon.setIconSize(14);
        Button refreshBtn = new Button("Refresh", refreshIcon);
        refreshBtn.getStyleClass().add("btn-secondary");
        refreshBtn.setOnAction(e -> refresh());

        HBox topBar = new HBox(10, onlineCountLabel, new Region(), refreshBtn);
        HBox.setHgrow(topBar.getChildren().get(1), Priority.ALWAYS);
        topBar.setAlignment(Pos.CENTER_LEFT);

        // Cards grid
        slavesGrid = new VBox(16);

        VBox content = new VBox(12, topBar, slavesGrid);
        content.getStyleClass().add("card");

        getChildren().addAll(header, content);
        refresh();
    }

    public void refresh() {
        Thread t = new Thread(() -> {
            NetworkMessage resp = connection.sendRequest(NetworkMessage.of(Type.GET_SLAVES));
            Platform.runLater(() -> {
                slavesGrid.getChildren().clear();
                if (resp != null && resp.isSuccess() && resp.getPayload() instanceof List<?> list) {
                    long online = list.stream().map(o -> (Map<?, ?>) o)
                            .filter(m -> !"OFFLINE".equals(m.get("status"))).count();
                    onlineCountLabel.setText(online + " / " + list.size() + " Slaves online");

                    for (Object item : list) {
                        if (item instanceof SlaveNode node) {
                            slavesGrid.getChildren().add(buildSlaveCard(node));
                        } else if (item instanceof Map<?, ?> m) {
                            slavesGrid.getChildren().add(buildSlaveCardFromMap(m));
                        }
                    }
                } else {
                    // Show offline placeholders for all 3
                    String[] names = {"Mercury", "Venus", "Mars"};
                    int[] ports = {9092, 9093, 9094};
                    for (int i = 0; i < names.length; i++) {
                        slavesGrid.getChildren().add(buildOfflinePlaceholder(names[i], ports[i]));
                    }
                    onlineCountLabel.setText("0 / 3 Slaves online");
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private HBox buildSlaveCardFromMap(Map<?, ?> m) {
        String name = str(m, "name", "Unknown");
        String statusStr = str(m, "status", "OFFLINE");
        String host = str(m, "host", "--");
        String javaVer = str(m, "javaVersion", "--");
        String osName = str(m, "osName", "--");
        String currentTask = str(m, "currentTaskTitle", null);
        int completed = intVal(m, "completedTasks");
        int failed = intVal(m, "failedTasks");
        int active = intVal(m, "activeTasks");
        long uptime = longVal(m, "uptimeMs");
        int port = intVal(m, "port");

        @SuppressWarnings("unchecked")
        List<Number> loadHistory = m.get("recentLoadHistory") instanceof List<?> l
                ? (List<Number>) l : List.of();

        boolean offline = "OFFLINE".equals(statusStr);

        return buildCard(name, port, statusStr, host, javaVer, osName,
                currentTask, completed, failed, active, uptime, loadHistory, offline);
    }

    private HBox buildSlaveCard(SlaveNode node) {
        boolean offline = node.getStatus() == SlaveNode.Status.OFFLINE;
        List<Number> loadHistory = node.getRecentLoadHistory() == null
                ? List.of()
                : node.getRecentLoadHistory().stream().map(n -> (Number) n).toList();

        return buildCard(
                node.getName(), node.getPort(),
                node.getStatus() != null ? node.getStatus().name() : "OFFLINE",
                node.getHost() != null ? node.getHost() : "--",
                node.getJavaVersion() != null ? node.getJavaVersion() : "--",
                node.getOsName() != null ? node.getOsName() : "--",
                node.getCurrentTaskTitle(),
                node.getCompletedTasks(), node.getFailedTasks(), node.getActiveTasks(),
                node.getUptimeMs(), loadHistory, offline);
    }

    private HBox buildOfflinePlaceholder(String name, int port) {
        return buildCard(name, port, "OFFLINE", "--", "--", "--",
                null, 0, 0, 0, 0, List.of(), true);
    }

    private HBox buildCard(String name, int port, String statusStr,
                            String host, String javaVer, String osName,
                            String currentTask, int completed, int failed,
                            int active, long uptimeMs, List<Number> loadHistory,
                            boolean offline) {

        HBox card = new HBox(20);
        card.getStyleClass().add("slave-card");
        card.setPadding(new Insets(16));
        card.setAlignment(Pos.CENTER_LEFT);
        card.setStyle("-fx-background-color: -dc-bg-hover; -fx-background-radius: 10; -fx-border-radius: 10;" +
                (offline ? " -fx-opacity: 0.55;" : ""));

        // Planet icon
        FontIcon planetIcon = new FontIcon(getPlanetIcon(name));
        planetIcon.setIconColor(offline ? Color.web("#555b70") : Color.web(getPlanetColor(name)));
        planetIcon.setIconSize(36);
        StackPane iconPane = new StackPane(planetIcon);
        iconPane.setPrefSize(52, 52);
        iconPane.setStyle("-fx-background-color: " + (offline ? "#2a2d3e" : getPlanetBg(name)) +
                "; -fx-background-radius: 50%;");

        // Main info
        Label nameLbl = new Label(name);
        nameLbl.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -dc-text-primary;");

        Label statusBadge = new Label(statusStr);
        statusBadge.getStyleClass().add("status-badge");
        statusBadge.setStyle(getStatusStyle(statusStr));
        statusBadge.setPadding(new Insets(2, 8, 2, 8));

        HBox nameRow = new HBox(8, nameLbl, statusBadge);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        Label portLbl = new Label("Port " + port);
        portLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -dc-text-secondary;");

        Label hostLbl = new Label(offline ? "Offline" : host);
        hostLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -dc-text-secondary;");

        Label systemLbl = new Label(offline ? "" : "Java " + javaVer + " · " + osName);
        systemLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #555b70;");

        String taskText = (currentTask != null && !currentTask.isBlank())
                ? "⚡ " + currentTask : (offline ? "Offline" : "Idle");
        Label taskLbl = new Label(taskText);
        taskLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: " +
                (currentTask != null && !currentTask.isBlank() ? "#fbbf24" : "-dc-text-secondary") + ";");

        VBox infoBox = new VBox(3, nameRow, portLbl, hostLbl, systemLbl, taskLbl);
        HBox.setHgrow(infoBox, Priority.ALWAYS);

        // Stats column
        VBox statsBox = buildStatsBox(completed, failed, active, uptimeMs);

        // Workload history bar chart
        VBox historyBox = buildLoadHistory(loadHistory, offline);

        card.getChildren().addAll(iconPane, infoBox, statsBox, historyBox);
        return card;
    }

    private VBox buildStatsBox(int completed, int failed, int active, long uptimeMs) {
        Label statsTitle = new Label("STATS");
        statsTitle.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #555b70;");

        /*Label completedLbl = new Label("✓ " + completed + " completed");
        completedLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #34d399;");

        Label failedLbl = new Label("✗ " + failed + " failed");
        failedLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #f87171;");

        Label activeLbl = new Label("⚡ " + active + " active");
        activeLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -dc-text-secondary;");*/

        //newershit
        FontIcon checkIcon = new FontIcon("fas-check");
        checkIcon.setIconSize(11);
        checkIcon.setIconColor(javafx.scene.paint.Color.web("#34d399"));
        Label completedLbl = new Label(" " + completed + " completed");
        completedLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #34d399;");
        HBox completedRow = new HBox(4, checkIcon, completedLbl);
        completedRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        FontIcon timesIcon = new FontIcon("fas-times");
        timesIcon.setIconSize(11);
        timesIcon.setIconColor(javafx.scene.paint.Color.web("#f87171"));
        Label failedLbl = new Label(" " + failed + " failed");
        failedLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #f87171;");
        HBox failedRow = new HBox(4, timesIcon, failedLbl);
        failedRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        FontIcon boltIcon = new FontIcon("fas-bolt");
        boltIcon.setIconSize(11);
        boltIcon.setIconColor(javafx.scene.paint.Color.web("#8b94b0"));
        Label activeLbl = new Label(" " + active + " active");
        activeLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -dc-text-secondary;");
        HBox activeRow = new HBox(4, boltIcon, activeLbl);
        activeRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        //newershit

        String uptimeStr = formatUptime(uptimeMs);
        //Label uptimeLbl = new Label("↑ " + uptimeStr);
        //uptimeLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -dc-text-secondary;");

        //newshit
        FontIcon clockIcon = new FontIcon("fas-clock");
        clockIcon.setIconSize(11);
        clockIcon.setIconColor(javafx.scene.paint.Color.web("#8b94b0"));
        Label uptimeLbl = new Label(" " + uptimeStr);
        uptimeLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -dc-text-secondary;");
        HBox uptimeRow = new HBox(4, clockIcon, uptimeLbl);
        uptimeRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        //newshit

        VBox box = new VBox(4, statsTitle, completedRow, failedRow, activeRow, uptimeRow);
        box.setPrefWidth(130);
        return box;
    }

    private VBox buildLoadHistory(List<Number> history, boolean offline) {
        Label histTitle = new Label("LOAD HISTORY");
        histTitle.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #555b70;");

        HBox bars = new HBox(3);
        bars.setAlignment(Pos.BOTTOM_LEFT);
        bars.setPrefHeight(40);

        int barCount = 10;
        // Pad with zeros if not enough history
        int start = Math.max(0, history.size() - barCount);
        List<Number> recent = history.subList(start, history.size());

        for (int i = 0; i < barCount; i++) {
            int val = i < recent.size() ? recent.get(i).intValue() : 0;
            double heightPct = Math.min(1.0, val / 3.0); // max 3 tasks
            double barHeight = Math.max(4, heightPct * 36);

            Region bar = new Region();
            bar.setPrefWidth(8);
            bar.setPrefHeight(barHeight);
            String color = offline ? "#2a2d3e" : (val == 0 ? "#2a3050" : "#6366f1");
            bar.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 2;");
            bars.getChildren().add(bar);
        }

        Label noData = new Label(offline ? "Offline" : (history.isEmpty() ? "No data yet" : ""));
        noData.setStyle("-fx-font-size: 10px; -fx-text-fill: #555b70;");

        VBox box = new VBox(4);
        box.getChildren().add(histTitle);
        box.getChildren().add(history.isEmpty() ? noData : bars);
        box.setPrefWidth(110);
        return box;
    }

    private String getPlanetIcon(String name) {
        return switch (name) {
            case "Mercury" -> "fas-circle";
            case "Venus" -> "fas-globe";
            case "Mars" -> "fas-meteor";
            default -> "fas-server";
        };
    }

    private String getPlanetColor(String name) {
        return switch (name) {
            case "Mercury" -> "#94a3b8";
            case "Venus" -> "#fbbf24";
            case "Mars" -> "#f87171";
            default -> "#6366f1";
        };
    }

    private String getPlanetBg(String name) {
        return switch (name) {
            case "Mercury" -> "#1e2235";
            case "Venus" -> "#2a1f10";
            case "Mars" -> "#2a1010";
            default -> "#1a1d2e";
        };
    }

    private String getStatusStyle(String status) {
        return switch (status) {
            case "ONLINE" -> "-fx-background-color: #064e3b; -fx-text-fill: #34d399; -fx-background-radius: 20;";
            case "BUSY" -> "-fx-background-color: #3b2800; -fx-text-fill: #fbbf24; -fx-background-radius: 20;";
            case "OFFLINE" -> "-fx-background-color: #1f2937; -fx-text-fill: #6b7280; -fx-background-radius: 20;";
            default -> "-fx-background-color: #1f2937; -fx-text-fill: #6b7280; -fx-background-radius: 20;";
        };
    }

    private String formatUptime(long ms) {
        if (ms <= 0) return "--";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m " + (s % 60) + "s";
        return (m / 60) + "h " + (m % 60) + "m";
    }

    private String str(Map<?, ?> m, String key, String def) {
        Object v = m.get(key);
        return v != null && !v.toString().equals("null") ? v.toString() : def;
    }

    private int intVal(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    private long longVal(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
}
