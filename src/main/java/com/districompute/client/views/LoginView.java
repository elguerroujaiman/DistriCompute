package com.districompute.client.views;

import com.districompute.client.ClientSession;
import com.districompute.client.ThemeManager;
import com.districompute.client.components.LoadingOverlay;
import com.districompute.client.components.ToastNotification;
import com.districompute.common.models.NetworkMessage;
import com.districompute.common.models.NetworkMessage.Type;
import com.districompute.common.utils.ConnectionConfig;
import com.districompute.network.ServerConnection;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.scene.paint.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.scene.text.*;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Login and Registration screen for DistriCompute.
 * Includes connection status indicator and retry button.
 */
public class LoginView extends StackPane {

    private final ServerConnection connection;
    private Consumer<Map<String, Object>> onLoginSuccess;
    private LoadingOverlay loadingOverlay;

    private boolean showingRegister = false;

    // Login fields
    private TextField loginUsername;
    private PasswordField loginPassword;
    private Label loginError;
    private Button loginBtn;

    // Register fields
    private TextField regUsername;
    private PasswordField regPassword;
    private PasswordField regConfirm;
    private TextField regEmail;
    private Label regError;
    private Button registerBtn;

    private VBox loginCard;
    private VBox registerCard;

    // Connection status
    private Circle connectionDot;
    private Label connectionLabel;
    private Button retryBtn;
    private Label connectionFeedback;

    public LoginView(ServerConnection connection) {
        this.connection = connection;
        buildUI();
        startConnectionMonitor();
    }

    private void buildUI() {
        getStyleClass().add("login-root");
        setPrefSize(1200, 750);

        StackPane cardContainer = new StackPane();

        loginCard = buildLoginCard();
        registerCard = buildRegisterCard();
        registerCard.setVisible(false);
        registerCard.setManaged(false);

        cardContainer.getChildren().addAll(loginCard, registerCard);
        cardContainer.setAlignment(Pos.CENTER);

        VBox connectionSettingsPanel = buildConnectionSettingsPanel();

        // Connection status bar at bottom-left
        HBox connectionBar = buildConnectionBar();

        getChildren().addAll(cardContainer, connectionSettingsPanel, connectionBar);
        StackPane.setAlignment(connectionSettingsPanel, Pos.BOTTOM_RIGHT);
        setAlignment(Pos.CENTER);
        StackPane.setAlignment(connectionBar, Pos.BOTTOM_LEFT);

        // Entrance animation
        loginCard.setTranslateY(30);
        loginCard.setOpacity(0);
        Timeline entrance = new Timeline(
                new KeyFrame(Duration.millis(400),
                        new KeyValue(loginCard.translateYProperty(), 0, Interpolator.EASE_OUT),
                        new KeyValue(loginCard.opacityProperty(), 1, Interpolator.EASE_OUT))
        );
        entrance.play();
        loadingOverlay = new LoadingOverlay();
        getChildren().add(loadingOverlay);
    }

    private VBox buildConnectionSettingsPanel() {
        ConnectionConfig connConfig = ConnectionConfig.getInstance();

        ToggleGroup modeGroup = new ToggleGroup();

        RadioButton localBtn = new RadioButton("Local");
        localBtn.setToggleGroup(modeGroup);
        localBtn.setSelected(connConfig.getMode() == ConnectionConfig.Mode.LOCAL);
        localBtn.getStyleClass().add("task-meta-label");

        RadioButton lanBtn = new RadioButton("LAN");
        lanBtn.setToggleGroup(modeGroup);
        lanBtn.setSelected(connConfig.getMode() == ConnectionConfig.Mode.LAN);
        lanBtn.getStyleClass().add("task-meta-label");

        RadioButton remoteBtn = new RadioButton("Remote");
        remoteBtn.setToggleGroup(modeGroup);
        remoteBtn.setSelected(connConfig.getMode() == ConnectionConfig.Mode.REMOTE);
        remoteBtn.getStyleClass().add("task-meta-label");

        HBox modeRow = new HBox(12, localBtn, lanBtn, remoteBtn);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        TextField lanHostField = new TextField(connConfig.getLanHost());
        lanHostField.setPromptText("e.g. 192.168.1.7");
        lanHostField.setMaxWidth(200);

        TextField remoteHostField = new TextField(connConfig.getRemoteHost());
        remoteHostField.setPromptText("e.g. 0.tcp.ngrok.io");
        remoteHostField.setMaxWidth(200);

        TextField remotePortField = new TextField(String.valueOf(connConfig.getRemotePort()));
        remotePortField.setPromptText("e.g. 12345");
        remotePortField.setMaxWidth(100);

        HBox remoteRow = new HBox(8, remoteHostField, remotePortField);
        remoteRow.setAlignment(Pos.CENTER_LEFT);

        VBox lanFields = new VBox(4, lanHostField);
        lanFields.setVisible(connConfig.getMode() == ConnectionConfig.Mode.LAN);
        lanFields.setManaged(connConfig.getMode() == ConnectionConfig.Mode.LAN);

        VBox remoteFields = new VBox(4, remoteRow);
        remoteFields.setVisible(connConfig.getMode() == ConnectionConfig.Mode.REMOTE);
        remoteFields.setManaged(connConfig.getMode() == ConnectionConfig.Mode.REMOTE);

        modeGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            lanFields.setVisible(newVal == lanBtn);
            lanFields.setManaged(newVal == lanBtn);
            remoteFields.setVisible(newVal == remoteBtn);
            remoteFields.setManaged(newVal == remoteBtn);
        });

        Button applyBtn = new Button("Apply & Reconnect");
        applyBtn.getStyleClass().add("btn-secondary");
        applyBtn.setOnAction(e -> {
            if (lanBtn.isSelected()) {
                connConfig.setMode(ConnectionConfig.Mode.LAN);
                connConfig.setLanHost(lanHostField.getText().trim());
            } else if (remoteBtn.isSelected()) {
                connConfig.setMode(ConnectionConfig.Mode.REMOTE);
                connConfig.setRemoteHost(remoteHostField.getText().trim());
                try {
                    connConfig.setRemotePort(Integer.parseInt(remotePortField.getText().trim()));
                } catch (NumberFormatException ex) {
                    connectionFeedback.setText("Invalid port number.");
                    connectionFeedback.setStyle("-fx-font-size: 11px; -fx-text-fill: #f5564a;");
                    return;
                }
            } else {
                connConfig.setMode(ConnectionConfig.Mode.LOCAL);
            }
            retryConnection();
        });

        VBox panel = new VBox(8, modeRow, lanFields, remoteFields, applyBtn);
        panel.setPadding(new Insets(12, 20, 16, 20));
        panel.setMaxWidth(Region.USE_PREF_SIZE);
        panel.setMaxHeight(Region.USE_PREF_SIZE);
        panel.setPickOnBounds(false);
        return panel;
    }

    private HBox buildConnectionBar() {
        connectionDot = new Circle(5);
        connectionLabel = new Label("Checking...");
        connectionLabel.getStyleClass().add("connection-label");

        FontIcon retryIcon = new FontIcon("fas-sync-alt");
        retryIcon.setIconSize(12);
        retryIcon.setIconColor(Color.web("#8b90a0"));

        retryBtn = new Button();
        retryBtn.setGraphic(retryIcon);
        retryBtn.getStyleClass().add("icon-button");
        retryBtn.setVisible(false);
        retryBtn.setManaged(false);
        Tooltip.install(retryBtn, new Tooltip("Retry connection"));
        retryBtn.setOnAction(e -> retryConnection());

        connectionFeedback = new Label("");
        connectionFeedback.setStyle("-fx-font-size: 11px;");
        connectionFeedback.setPickOnBounds(false);

        HBox bar = new HBox(8, connectionDot, connectionLabel, retryBtn, connectionFeedback);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12, 20, 16, 20));
        bar.setPickOnBounds(false);
        bar.setMaxWidth(Region.USE_PREF_SIZE);
        bar.setMaxHeight(Region.USE_PREF_SIZE);
        bar.setMouseTransparent(false);
        return bar;
    }

    private void updateConnectionStatus(boolean isConnected) {
        Platform.runLater(() -> {
            if (isConnected) {
                connectionDot.setFill(Color.web("#2dd69b"));
                connectionLabel.setText("Connected");
                retryBtn.setVisible(false);
                retryBtn.setManaged(false);
            } else {
                connectionDot.setFill(Color.web("#f5564a"));
                connectionLabel.setText("Disconnected");
                retryBtn.setVisible(true);
                retryBtn.setManaged(true);
            }
        });
    }

    private void retryConnection() {
        connectionLabel.setText("Connecting...");
        connectionDot.setFill(Color.web("#f5a623"));
        connectionFeedback.setText("");
        retryBtn.setDisable(true);

        Thread t = new Thread(() -> {
            ConnectionConfig connConfig = ConnectionConfig.getInstance();
            boolean success = connection.connect(connConfig.getEffectiveHost(), connConfig.getEffectivePort());
            Platform.runLater(() -> {
                retryBtn.setDisable(false);
                updateConnectionStatus(success);
                if (success) {
                    connectionFeedback.setText("Connected successfully!");
                    connectionFeedback.setStyle("-fx-font-size: 11px; -fx-text-fill: #2dd69b;");
                } else {
                    connectionFeedback.setText("Could not connect to server.");
                    connectionFeedback.setStyle("-fx-font-size: 11px; -fx-text-fill: #f5564a;");
                }
                // Auto-clear feedback after 5 seconds
                javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(5));
                pause.setOnFinished(e2 -> connectionFeedback.setText(""));
                pause.play();
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private void startConnectionMonitor() {
        updateConnectionStatus(connection.isConnected());
        connection.setConnectionStatusCallback(this::updateConnectionStatus);
    }

    // -------------------------------------------------------------------------
    // Login Card
    // -------------------------------------------------------------------------

    private VBox buildLoginCard() {
        VBox card = new VBox(20);
        card.getStyleClass().add("login-card");
        card.setAlignment(Pos.CENTER_LEFT);

        HBox logoArea = buildLogoArea();

        Label title = new Label("Welcome back");
        title.getStyleClass().add("login-title");
        Label subtitle = new Label("Sign in to DistriCompute");
        subtitle.getStyleClass().add("login-subtitle");
        VBox titleBox = new VBox(4, title, subtitle);

        Label userLabel = new Label("Username");
        userLabel.getStyleClass().add("form-label");
        loginUsername = new TextField();
        loginUsername.setPromptText("Enter your username");
        loginUsername.setMaxWidth(Double.MAX_VALUE);

        Label passLabel = new Label("Password");
        passLabel.getStyleClass().add("form-label");
        loginPassword = new PasswordField();
        loginPassword.setPromptText("Enter your password");
        loginPassword.setMaxWidth(Double.MAX_VALUE);

        loginError = new Label("");
        loginError.getStyleClass().add("error-label");
        loginError.setWrapText(true);
        loginError.setVisible(false);

        loginBtn = new Button("Sign In");
        loginBtn.getStyleClass().add("btn-primary");
        loginBtn.setMaxWidth(Double.MAX_VALUE);
        loginBtn.setOnAction(e -> handleLogin());

        HBox registerRow = new HBox(4);
        registerRow.setAlignment(Pos.CENTER);
        Label noAccount = new Label("Don't have an account?");
        noAccount.getStyleClass().add("login-subtitle");
        Label registerLink = new Label("Register");
        registerLink.getStyleClass().add("link-label");
        registerLink.setOnMouseClicked(e -> switchToRegister());
        registerRow.getChildren().addAll(noAccount, registerLink);

        loginPassword.setOnAction(e -> handleLogin());
        loginUsername.setOnAction(e -> loginPassword.requestFocus());

        card.getChildren().addAll(
                logoArea, titleBox,
                userLabel, loginUsername,
                passLabel, loginPassword,
                loginError, loginBtn, registerRow
        );
        return card;
    }

    // -------------------------------------------------------------------------
    // Register Card
    // -------------------------------------------------------------------------

    private VBox buildRegisterCard() {
        VBox card = new VBox(16);
        card.getStyleClass().add("login-card");
        card.setAlignment(Pos.CENTER_LEFT);

        HBox logoArea = buildLogoArea();

        Label title = new Label("Create Account");
        title.getStyleClass().add("login-title");
        Label subtitle = new Label("Join DistriCompute today");
        subtitle.getStyleClass().add("login-subtitle");
        VBox titleBox = new VBox(4, title, subtitle);

        Label userLabel = new Label("Username");
        userLabel.getStyleClass().add("form-label");
        regUsername = new TextField();
        regUsername.setPromptText("Choose a username (min 3 chars)");
        regUsername.setMaxWidth(Double.MAX_VALUE);

        Label emailLabel = new Label("Email (optional)");
        emailLabel.getStyleClass().add("form-label");
        regEmail = new TextField();
        regEmail.setPromptText("your@email.com");
        regEmail.setMaxWidth(Double.MAX_VALUE);

        Label passLabel = new Label("Password");
        passLabel.getStyleClass().add("form-label");
        regPassword = new PasswordField();
        regPassword.setPromptText("At least 6 characters");
        regPassword.setMaxWidth(Double.MAX_VALUE);

        Label confirmLabel = new Label("Confirm Password");
        confirmLabel.getStyleClass().add("form-label");
        regConfirm = new PasswordField();
        regConfirm.setPromptText("Repeat your password");
        regConfirm.setMaxWidth(Double.MAX_VALUE);

        regError = new Label("");
        regError.getStyleClass().add("error-label");
        regError.setWrapText(true);
        regError.setVisible(false);

        registerBtn = new Button("Create Account");
        registerBtn.getStyleClass().add("btn-primary");
        registerBtn.setMaxWidth(Double.MAX_VALUE);
        registerBtn.setOnAction(e -> handleRegister());

        HBox loginRow = new HBox(4);
        loginRow.setAlignment(Pos.CENTER);
        Label hasAccount = new Label("Already have an account?");
        hasAccount.getStyleClass().add("login-subtitle");
        Label loginLink = new Label("Sign in");
        loginLink.getStyleClass().add("link-label");
        loginLink.setOnMouseClicked(e -> switchToLogin());
        loginRow.getChildren().addAll(hasAccount, loginLink);

        card.getChildren().addAll(
                logoArea, titleBox,
                userLabel, regUsername,
                emailLabel, regEmail,
                passLabel, regPassword,
                confirmLabel, regConfirm,
                regError, registerBtn, loginRow
        );
        return card;
    }

    // -------------------------------------------------------------------------
    // Logo
    // -------------------------------------------------------------------------

    private HBox buildLogoArea() {
        Pane logoIcon = createLogoIcon(32);
        Label logoText = new Label("DistriCompute");
        logoText.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -dc-text-primary;");
        HBox logo = new HBox(10, logoIcon, logoText);
        logo.setAlignment(Pos.CENTER_LEFT);
        return logo;
    }

    public static Pane createLogoIcon(double size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.setMinSize(size, size);

        double cx = size / 2, cy = size / 2;
        double r = size * 0.12;

        Circle center = new Circle(cx, cy, r);
        center.setFill(Color.web("#4f8ef7"));

        double[][] positions = {
                {cx - size * 0.3, cy - size * 0.25},
                {cx + size * 0.3, cy - size * 0.25},
                {cx - size * 0.3, cy + size * 0.25},
                {cx + size * 0.3, cy + size * 0.25},
        };
        String[] colors = {"#7c5cbf", "#2dd69b", "#f5a623", "#f5564a"};

        for (int i = 0; i < positions.length; i++) {
            Line line = new Line(cx, cy, positions[i][0], positions[i][1]);
            line.setStroke(Color.web("#4f8ef7", 0.4));
            line.setStrokeWidth(1.5);
            pane.getChildren().add(line);

            Circle node = new Circle(positions[i][0], positions[i][1], r * 0.75);
            node.setFill(Color.web(colors[i]));
            pane.getChildren().add(node);
        }

        pane.getChildren().add(center);
        return pane;
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void handleLogin() {
        String username = loginUsername.getText().trim();
        String password = loginPassword.getText();

        if (username.isEmpty() || password.isEmpty()) {
            showLoginError("Please fill in all fields.");
            return;
        }

        // Check connection BEFORE showing loading screen
        if (!connection.isConnected()) {
            showLoginError("Not connected to server. Check connection status below and retry.");
            return;
        }

        loginBtn.setDisable(true);
        loadingOverlay.show("Authenticating...");
        loginBtn.setText("Signing in...");
        loginError.setVisible(false);

        Map<String, String> creds = new LinkedHashMap<>();
        creds.put("username", username);
        creds.put("password", password);

        Thread t = new Thread(() -> {
            NetworkMessage response = connection.sendRequest(
                    new NetworkMessage(Type.LOGIN_REQUEST, creds));

            Platform.runLater(() -> {
                loadingOverlay.hide();
                loginBtn.setDisable(false);
                loginBtn.setText("Sign In");

                if (response.isSuccess()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> userData = (Map<String, Object>) response.getPayload();
                    ClientSession.getInstance().setFromMap(userData);
                    connection.setConnectionStatusCallback(null);
                    if (onLoginSuccess != null) onLoginSuccess.accept(userData);
                } else {
                    showLoginError(response.getMessage());
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private void handleRegister() {
        String username = regUsername.getText().trim();
        String password = regPassword.getText();
        String confirm = regConfirm.getText();
        String email = regEmail.getText().trim();

        if (username.isEmpty() || password.isEmpty() || confirm.isEmpty()) {
            showRegisterError("Please fill in all required fields.");
            return;
        }
        if (username.length() < 3) {
            showRegisterError("Username must be at least 3 characters.");
            return;
        }
        if (password.length() < 6) {
            showRegisterError("Password must be at least 6 characters.");
            return;
        }
        if (!password.equals(confirm)) {
            showRegisterError("Passwords do not match.");
            return;
        }

        // Check connection BEFORE showing loading screen
        if (!connection.isConnected()) {
            showRegisterError("Not connected to server. Check connection status below and retry.");
            return;
        }

        registerBtn.setDisable(true);
        registerBtn.setText("Creating account...");
        regError.setVisible(false);
        loadingOverlay.show("Creating account...");

        Map<String, String> data = new LinkedHashMap<>();
        data.put("username", username);
        data.put("password", password);
        data.put("email", email);

        Thread t = new Thread(() -> {
            NetworkMessage response = connection.sendRequest(
                    new NetworkMessage(Type.REGISTER_REQUEST, data));

            Platform.runLater(() -> {
                loadingOverlay.hide();
                registerBtn.setDisable(false);
                registerBtn.setText("Create Account");

                if (response.isSuccess()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> userData = (Map<String, Object>) response.getPayload();
                    ClientSession.getInstance().setFromMap(userData);
                    connection.setConnectionStatusCallback(null);
                    if (onLoginSuccess != null) onLoginSuccess.accept(userData);
                } else {
                    showRegisterError(response.getMessage());
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private void switchToRegister() {
        FadeTransition fadeOut = new FadeTransition(Duration.millis(200), loginCard);
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(e -> {
            loginCard.setVisible(false);
            loginCard.setManaged(false);
            registerCard.setVisible(true);
            registerCard.setManaged(true);
            registerCard.setOpacity(0);
            registerCard.setTranslateX(20);
            FadeTransition fadeIn = new FadeTransition(Duration.millis(200), registerCard);
            fadeIn.setToValue(1);
            TranslateTransition slide = new TranslateTransition(Duration.millis(200), registerCard);
            slide.setToX(0);
            new ParallelTransition(fadeIn, slide).play();
        });
        fadeOut.play();
        showingRegister = true;
    }

    private void switchToLogin() {
        FadeTransition fadeOut = new FadeTransition(Duration.millis(200), registerCard);
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(e -> {
            registerCard.setVisible(false);
            registerCard.setManaged(false);
            loginCard.setVisible(true);
            loginCard.setManaged(true);
            loginCard.setOpacity(0);
            loginCard.setTranslateX(-20);
            FadeTransition fadeIn = new FadeTransition(Duration.millis(200), loginCard);
            fadeIn.setToValue(1);
            TranslateTransition slide = new TranslateTransition(Duration.millis(200), loginCard);
            slide.setToX(0);
            new ParallelTransition(fadeIn, slide).play();
        });
        fadeOut.play();
        showingRegister = false;
    }

    private void showLoginError(String msg) {
        loginError.setText(msg);
        loginError.setVisible(true);
    }

    private void showRegisterError(String msg) {
        regError.setText(msg);
        regError.setVisible(true);
    }

    public void setOnLoginSuccess(Consumer<Map<String, Object>> callback) {
        this.onLoginSuccess = callback;
    }
}
