package com.districompute.client.controllers;

import com.districompute.client.ClientSession;
import com.districompute.client.ThemeManager;
import com.districompute.client.components.AvatarCircle;
import com.districompute.client.components.LoadingOverlay;
import com.districompute.client.components.ToastNotification;
import com.districompute.client.views.*;
import com.districompute.common.models.NetworkMessage;
import com.districompute.common.utils.ConnectionConfig;
import com.districompute.network.ServerConnection;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Main application controller — manages the sidebar, top bar, content area, and navigation.
 */
public class MainController extends BorderPane {

    private final ServerConnection connection;
    private final ClientSession session = ClientSession.getInstance();

    private LoadingOverlay loadingOverlay;

    private VBox sidebar;
    private StackPane contentArea;
    private Label topbarTitle;
    private Circle connectionDot;
    private Label connectionLabel;
    private Button retryConnectionBtn;
    private AvatarCircle avatarCircle;
    private Label notificationBadge;
    private int unreadNotifications = 0;

    // Notification popup toggle
    private javafx.stage.Popup activeNotificationPopup;

    private String currentPage = "";

    // Views
    private DashboardView dashboardView;
    private SubmitTaskView submitTaskView;
    private MyTasksView myTasksView;
    private UsersView usersView;
    private LogsView logsView;
    private SettingsView settingsView;

    // Nav items: id -> button
    private final Map<String, Button> navButtons = new LinkedHashMap<>();

    public MainController(ServerConnection connection) {
        this.connection = connection;
        buildLayout();
        setupServerPushListener();
        navigateTo("DASHBOARD");
        startConnectionMonitor();
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private void buildLayout() {
        setLeft(buildSidebar());
        setTop(buildTopBar());

        contentArea = new StackPane();
        contentArea.setPadding(Insets.EMPTY);
        setCenter(contentArea);
        loadingOverlay = new LoadingOverlay();
        contentArea.getChildren().add(loadingOverlay);
    }

    private VBox buildSidebar() {
        sidebar = new VBox();
        sidebar.getStyleClass().add("sidebar");

        // Logo area
        Pane logoIcon = LoginView.createLogoIcon(28);

        Label logoText = new Label("DistriCompute");
        logoText.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: -dc-text-primary;");

        Label logoSub = new Label("Distributed Computing");
        logoSub.getStyleClass().add("sidebar-logo-sub");

        VBox logoTextBox = new VBox(2, logoText, logoSub);
        HBox logoArea = new HBox(10, logoIcon, logoTextBox);
        logoArea.getStyleClass().add("sidebar-logo-area");
        logoArea.setAlignment(Pos.CENTER_LEFT);
        logoArea.setPadding(new Insets(20, 20, 16, 20));

        // Navigation
        VBox nav = new VBox(4);
        nav.getStyleClass().add("sidebar-nav");
        nav.setPadding(new Insets(12, 10, 0, 10));

        addNavItem(nav, "DASHBOARD", "fas-tachometer-alt", "Dashboard");
        addNavItem(nav, "SUBMIT_TASK", "fas-upload", "Submit Task");
        addNavItem(nav, "MY_TASKS", "fas-tasks", "My Tasks");

        if (session.isAdmin()) {
            Separator sep = new Separator();
            sep.setPadding(new Insets(8, 16, 8, 16));
            nav.getChildren().add(sep);

            Label adminLabel = new Label("ADMIN");
            adminLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -dc-text-muted; -fx-padding: 0 0 4 8;");
            nav.getChildren().add(adminLabel);

            addNavItem(nav, "USERS", "fas-users", "Users");
            addNavItem(nav, "SLAVES", "fas-network-wired", "Slave Nodes");
            addNavItem(nav, "LOGS", "fas-terminal", "Server Console");
        }

        addNavItem(nav, "SETTINGS", "fas-cog", "Settings");

        // Spacer
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        // Connection indicator with retry button
        connectionDot = new Circle(5);
        connectionDot.setFill(Color.web("#2dd69b"));

        connectionLabel = new Label("Connected");
        connectionLabel.getStyleClass().add("connection-label");

        FontIcon retryIcon = new FontIcon("fas-sync-alt");
        retryIcon.setIconSize(12);
        retryIcon.setIconColor(Color.web("#8b90a0"));

        retryConnectionBtn = new Button();
        retryConnectionBtn.setGraphic(retryIcon);
        retryConnectionBtn.getStyleClass().add("icon-button");
        retryConnectionBtn.setVisible(false);
        retryConnectionBtn.setManaged(false);
        Tooltip.install(retryConnectionBtn, new Tooltip("Retry connection"));
        retryConnectionBtn.setOnAction(e -> {
            ConnectionConfig connConfig = ConnectionConfig.getInstance();
            connectionLabel.setText("Connecting...");
            connectionDot.setFill(Color.web("#f5a623"));
            retryConnectionBtn.setDisable(true);
            Thread t = new Thread(() -> {
                boolean ok = connection.connect(connConfig.getEffectiveHost(), connConfig.getEffectivePort());
                Platform.runLater(() -> {
                    retryConnectionBtn.setDisable(false);
                    if (ok) {
                        ToastNotification.show("Reconnected!", ToastNotification.ToastType.SUCCESS);
                    }
                });
            });
            t.setDaemon(true);
            t.start();
        });

        HBox connectionRow = new HBox(8, connectionDot, connectionLabel, retryConnectionBtn);
        connectionRow.setAlignment(Pos.CENTER_LEFT);
        connectionRow.setPadding(new Insets(12, 20, 16, 20));

        sidebar.getChildren().addAll(logoArea, nav, spacer, connectionRow);
        return sidebar;
    }

    private void addNavItem(VBox nav, String id, String iconCode, String label) {
        FontIcon icon = new FontIcon(iconCode);
        icon.setIconSize(15);

        Label lbl = new Label(label);
        lbl.getStyleClass().add("nav-item-label");

        HBox item = new HBox(12, icon, lbl);
        item.setAlignment(Pos.CENTER_LEFT);

        Button btn = new Button();
        btn.setGraphic(item);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.getStyleClass().add("nav-item");
        btn.setOnAction(e -> navigateTo(id));
        btn.setUserData(id);

        navButtons.put(id, btn);
        nav.getChildren().add(btn);
    }

    private HBox buildTopBar() {
        HBox topbar = new HBox();
        topbar.getStyleClass().add("topbar");
        topbar.setAlignment(Pos.CENTER);

        topbarTitle = new Label("Dashboard");
        topbarTitle.getStyleClass().add("topbar-title");
        topbarTitle.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(topbarTitle, Priority.ALWAYS);

        // Notification bell
        StackPane bellArea = buildNotificationBell();

        // Avatar + user info
        avatarCircle = new AvatarCircle(session.getInitials(), session.getAvatarColor(), 16);

        Label usernameLbl = new Label(session.getUsername());
        usernameLbl.getStyleClass().add("username-label");

        Label roleBadge = new Label(session.getRole());
        roleBadge.getStyleClass().addAll("role-badge", session.getRole().toLowerCase());

        VBox userInfo = new VBox(2, usernameLbl, roleBadge);
        userInfo.setAlignment(Pos.CENTER_LEFT);

        // Logout button
        Button logoutBtn = new Button();
        FontIcon logoutIcon = new FontIcon("fas-sign-out-alt");
        logoutIcon.setIconSize(16);
        logoutIcon.setIconColor(javafx.scene.paint.Color.web("#8b90a0"));
        logoutBtn.setGraphic(logoutIcon);
        logoutBtn.getStyleClass().add("icon-button");
        Tooltip.install(logoutBtn, new Tooltip("Logout"));
        logoutBtn.setOnAction(e -> handleLogout());

        HBox rightArea = new HBox(12, bellArea, avatarCircle, userInfo, logoutBtn);
        rightArea.setAlignment(Pos.CENTER);

        topbar.getChildren().addAll(topbarTitle, rightArea);
        topbar.setPadding(new Insets(0, 20, 0, 24));
        return topbar;
    }

    private StackPane buildNotificationBell() {
        FontIcon bell = new FontIcon("fas-bell");
        bell.setIconSize(17);
        bell.setIconColor(javafx.scene.paint.Color.web("#8b90a0"));

        notificationBadge = new Label("0");
        notificationBadge.setStyle(
                "-fx-background-color: #f5564a; -fx-text-fill: white; " +
                        "-fx-font-size: 9px; -fx-font-weight: bold; " +
                        "-fx-background-radius: 8; -fx-padding: 1 4 1 4;"
        );
        notificationBadge.setVisible(false);
        notificationBadge.setTranslateX(8);
        notificationBadge.setTranslateY(-8);

        StackPane bellPane = new StackPane(bell, notificationBadge);
        bellPane.setAlignment(Pos.TOP_RIGHT);

        Button bellBtn = new Button();
        bellBtn.setGraphic(bellPane);
        bellBtn.getStyleClass().add("icon-button");
        bellBtn.setOnAction(e -> toggleNotificationsPopup(bellBtn));

        return new StackPane(bellBtn);
    }

    // -------------------------------------------------------------------------
    // Navigation
    // -------------------------------------------------------------------------

    public void navigateTo(String pageId) {
        if (pageId.equals(currentPage)) return;
        currentPage = pageId;

        navButtons.forEach((id, btn) -> {
            if (id.equals(pageId)) {
                if (!btn.getStyleClass().contains("active")) btn.getStyleClass().add("active");
            } else {
                btn.getStyleClass().remove("active");
            }
        });

        topbarTitle.setText(switch (pageId) {
            case "DASHBOARD"   -> "Dashboard";
            case "SUBMIT_TASK" -> "Submit Task";
            case "MY_TASKS"    -> "My Tasks";
            case "USERS"       -> "User Management";
            case "SLAVES"      -> "Slave Nodes";
            case "LOGS"        -> "Server Console";
            case "SETTINGS"    -> "Settings";
            default            -> pageId;
        });

        Node view = getOrCreateView(pageId);

        loadingOverlay.show("Loading...");
        animateContentTransition(view);
    }

    private Node getOrCreateView(String pageId) {
        return switch (pageId) {
            case "DASHBOARD" -> {
                if (dashboardView == null) dashboardView = new DashboardView(connection, this);
                else dashboardView.refresh();
                yield dashboardView;
            }
            case "SUBMIT_TASK" -> {
                if (submitTaskView == null) {
                    submitTaskView = new SubmitTaskView(connection, () -> navigateTo("MY_TASKS"));
                } else {
                    submitTaskView.focusNextAvailableTab();
                }
                yield submitTaskView;
            }
            case "MY_TASKS" -> {
                if (myTasksView == null) myTasksView = new MyTasksView(connection);
                else myTasksView.refresh();
                yield myTasksView;
            }
            case "USERS" -> {
                if (usersView == null) usersView = new UsersView(connection);
                else usersView.refresh();
                yield usersView;
            }
            case "SLAVES" -> {
                SlavesView sv = new SlavesView(connection);
                yield sv;
            }
            case "LOGS" -> {
                if (logsView == null) logsView = new LogsView(connection);
                else logsView.refresh();
                yield logsView;
            }
            case "SETTINGS" -> {
                settingsView = new SettingsView();
                yield settingsView;
            }
            default -> new Label("Page not found: " + pageId);
        };
    }

    private void animateContentTransition(Node newView) {
        ScrollPane scrollPane = new ScrollPane(newView);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        scrollPane.setOpacity(0);
        scrollPane.setTranslateX(15);

        contentArea.getChildren().setAll(scrollPane);

        FadeTransition fade = new FadeTransition(Duration.millis(200), scrollPane);
        fade.setFromValue(0);
        fade.setToValue(1);

        TranslateTransition slide = new TranslateTransition(Duration.millis(200), scrollPane);
        slide.setFromX(15);
        slide.setToX(0);

        new ParallelTransition(fade, slide).play();
        loadingOverlay.hide();
    }

    // -------------------------------------------------------------------------
    // Server push listener
    // -------------------------------------------------------------------------

    private void setupServerPushListener() {
        connection.addObserver(message -> {
            if (message.getType() == NetworkMessage.Type.TASK_STATUS_UPDATE) {
                @SuppressWarnings("unchecked")
                Map<String, Object> task = (Map<String, Object>) message.getPayload();
                handleTaskUpdate(task);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void handleTaskUpdate(Map<String, Object> task) {
        String status = (String) task.getOrDefault("status", "");
        String title  = (String) task.getOrDefault("title", "Task");

        if (myTasksView != null) {
            myTasksView.handleTaskUpdate(task);
        }

        if (dashboardView != null && currentPage.equals("DASHBOARD")) {
            Platform.runLater(() -> dashboardView.refresh());
        }

        String notifMessage = switch (status) {
            case "COMPLETED" -> "'" + title + "' completed successfully";
            case "FAILED"    -> "'" + title + "' failed — check the error message";
            case "CANCELLED" -> "'" + title + "' was cancelled";
            case "EXECUTING" -> "'" + title + "' is now executing";
            default          -> null;
        };

        if (notifMessage != null) {
            session.addNotification("Task Update", notifMessage);
            Platform.runLater(() -> {
                unreadNotifications++;
                notificationBadge.setText(String.valueOf(unreadNotifications));
                notificationBadge.setVisible(true);

                ToastNotification.ToastType type = switch (status) {
                    case "COMPLETED" -> ToastNotification.ToastType.SUCCESS;
                    case "FAILED"    -> ToastNotification.ToastType.ERROR;
                    default          -> ToastNotification.ToastType.INFO;
                };
                ToastNotification.show(notifMessage, type);
            });
        }
    }

    /**
     * Toggles notification popup — clicking the bell while open closes it.
     */
    private void toggleNotificationsPopup(Button anchor) {
        // If popup is already showing, close it
        if (activeNotificationPopup != null && activeNotificationPopup.isShowing()) {
            activeNotificationPopup.hide();
            activeNotificationPopup = null;
            return;
        }

        session.markAllRead();
        unreadNotifications = 0;
        Platform.runLater(() -> notificationBadge.setVisible(false));

        javafx.stage.Popup popup = new javafx.stage.Popup();
        activeNotificationPopup = popup;

        VBox panel = new VBox(0);
        panel.getStyleClass().add("notification-panel");
        panel.setMinWidth(320);
        panel.setMaxWidth(320);
        panel.setMaxHeight(400);

        Label header = new Label("Notifications");
        header.setStyle(
                "-fx-font-size: 14px; -fx-font-weight: bold; " +
                        "-fx-text-fill: -dc-text-primary; -fx-padding: 12 16 10 16;"
        );
        panel.getChildren().add(header);

        List<String[]> notifs = session.getNotifications();
        if (notifs.isEmpty()) {
            Label empty = new Label("No notifications yet.");
            empty.setStyle("-fx-text-fill: -dc-text-muted; -fx-padding: 12 16 12 16;");
            panel.getChildren().add(empty);
        } else {
            VBox items = new VBox(0);
            int max = Math.min(8, notifs.size());
            for (int i = 0; i < max; i++) {
                String[] n = notifs.get(i);
                VBox item = new VBox(3);
                item.getStyleClass().add("notification-item");
                item.setPadding(new Insets(10, 16, 10, 16));

                Label t = new Label(n[0]);
                t.getStyleClass().add("notification-title");

                Label b = new Label(n[1]);
                b.getStyleClass().add("notification-body");
                b.setWrapText(true);

                Label time = new Label(n[2]);
                time.getStyleClass().add("notification-time");

                item.getChildren().addAll(t, b, time);
                items.getChildren().add(item);
            }

            ScrollPane scroll = new ScrollPane(items);
            scroll.setFitToWidth(true);
            scroll.setMaxHeight(350);
            scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
            panel.getChildren().add(scroll);
        }

        panel.getStylesheets().addAll(getScene().getStylesheets());
        popup.getContent().add(panel);
        popup.setAutoHide(true);
        popup.setOnHidden(e -> activeNotificationPopup = null);

        Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        popup.show(anchor, bounds.getMinX() - 270, bounds.getMaxY() + 5);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void handleLogout() {
        connection.setConnectionStatusCallback(null);
        connection.disconnect();
        session.clear();
        Platform.runLater(() -> {
            if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage stage) {
                com.districompute.client.ClientApp.showLogin(stage);
            }
        });
    }

    private void startConnectionMonitor() {
        connection.setConnectionStatusCallback(isConnected -> Platform.runLater(() -> {
            if (isConnected) {
                connectionDot.setFill(Color.web("#2dd69b"));
                connectionLabel.setText("Connected");
                retryConnectionBtn.setVisible(false);
                retryConnectionBtn.setManaged(false);
            } else {
                connectionDot.setFill(Color.web("#f5564a"));
                connectionLabel.setText("Disconnected");
                retryConnectionBtn.setVisible(true);
                retryConnectionBtn.setManaged(true);
                ToastNotification.show("Lost connection to server.", ToastNotification.ToastType.ERROR);
            }
        }));
    }
}
