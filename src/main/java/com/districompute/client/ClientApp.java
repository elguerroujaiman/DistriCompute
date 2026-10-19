package com.districompute.client;

import com.districompute.client.components.ToastNotification;
import com.districompute.client.controllers.MainController;
import com.districompute.client.views.LoginView;
import com.districompute.common.utils.ConnectionConfig;
import com.districompute.network.ServerConnection;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/**
 * DistriCompute Client Application entry point.
 *
 * Launches the JavaFX UI, connects to the server,
 * and manages the login → main window transition.
 */
public class ClientApp extends Application {

    private static ServerConnection connection;
    private static Stage primaryStage;

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        connection = new ServerConnection();

        ConnectionConfig connConfig = ConnectionConfig.getInstance();
        String host = connConfig.getEffectiveHost();
        int port = connConfig.getEffectivePort();

        // Try to connect (non-blocking — login screen handles connection status)
        connection.connect(host, port);

        stage.setTitle("DistriCompute");
        try {
            stage.getIcons().add(new Image(ClientApp.class.getResourceAsStream("/icon.png")));
        } catch (Exception ignored) {}
        stage.setMinWidth(1000);
        stage.setMinHeight(650);
        stage.setWidth(1280);
        stage.setHeight(800);
        stage.centerOnScreen();

        showLogin(stage);

        stage.setOnCloseRequest(e -> {
            if (connection != null) connection.disconnect();
            Platform.exit();
            System.exit(0);
        });

        stage.show();
    }

    /**
     * Shows the login screen.
     * Called on app start and after logout.
     */
    public static void showLogin(Stage stage) {
        ConnectionConfig connConfig = ConnectionConfig.getInstance();
        String host = connConfig.getEffectiveHost();
        int port = connConfig.getEffectivePort();

        if (!connection.isConnected()) {
            connection.connect(host, port);
        }
        LoginView loginView = new LoginView(connection);

        Scene scene = new Scene(loginView, stage.getWidth(), stage.getHeight());
        ThemeManager.registerScene(scene);
        ToastNotification.setOwnerStage(stage);

        loginView.setOnLoginSuccess(userData -> {
            showMainWindow(stage);
        });

        stage.setScene(scene);
    }

    /**
     * Shows the main application window after successful login.
     */
    public static void showMainWindow(Stage stage) {
        MainController mainController = new MainController(connection);

        Scene scene = new Scene(mainController, stage.getWidth(), stage.getHeight());
        ThemeManager.registerScene(scene);
        ToastNotification.setOwnerStage(stage);

        stage.setScene(scene);
        stage.setTitle("DistriCompute — " + ClientSession.getInstance().getUsername());
    }

    public static ServerConnection getConnection() { return connection; }

    @Override
    public void stop() {
        if (connection != null) connection.disconnect();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
