package com.districompute.client.views;

import com.districompute.client.ClientSession;
import com.districompute.client.ThemeManager;
import com.districompute.client.components.ToastNotification;
import com.districompute.common.utils.ConnectionConfig;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Settings view — theme toggle, connection mode, account info, about.
 */
public class SettingsView extends VBox {

    public SettingsView() {
        buildUI();
    }

    private void buildUI() {
        getStyleClass().add("content-area");
        setSpacing(24);

        Label title = new Label("Settings");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Customize your DistriCompute experience");
        subtitle.getStyleClass().add("page-subtitle");
        VBox header = new VBox(4, title, subtitle);

        VBox appearanceCard = buildAppearanceCard();
        VBox connectionCard = buildConnectionCard();
        VBox accountCard = buildAccountCard();
        VBox aboutCard = buildAboutCard();

        getChildren().addAll(header, appearanceCard, connectionCard, accountCard, aboutCard);
    }

    private VBox buildAppearanceCard() {
        Label title = new Label("APPEARANCE");
        title.getStyleClass().add("card-title");

        Label themeLabel = new Label("Theme");
        themeLabel.getStyleClass().add("form-label");

        boolean isDark = ThemeManager.isDark();

        ToggleGroup themeGroup = new ToggleGroup();

        RadioButton darkBtn = new RadioButton("Dark");
        darkBtn.setToggleGroup(themeGroup);
        darkBtn.setSelected(isDark);
        darkBtn.getStyleClass().add("task-meta-label");

        RadioButton lightBtn = new RadioButton("Light");
        lightBtn.setToggleGroup(themeGroup);
        lightBtn.setSelected(!isDark);
        lightBtn.getStyleClass().add("task-meta-label");

        Label hint = new Label("Theme is automatically saved for future sessions.");
        hint.getStyleClass().add("upload-zone-subtext");

        themeGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == darkBtn) {
                ThemeManager.setTheme(ThemeManager.Theme.DARK);
            } else {
                ThemeManager.setTheme(ThemeManager.Theme.LIGHT);
            }
        });

        HBox themeRow = new HBox(20, darkBtn, lightBtn);
        themeRow.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(12, title, themeLabel, themeRow, hint);
        card.getStyleClass().add("card");
        return card;
    }

    private VBox buildConnectionCard() {
        ConnectionConfig connConfig = ConnectionConfig.getInstance();

        Label title = new Label("CONNECTION");
        title.getStyleClass().add("card-title");

        Label modeLabel = new Label("Connection Mode");
        modeLabel.getStyleClass().add("form-label");

        ToggleGroup modeGroup = new ToggleGroup();

        RadioButton localBtn = new RadioButton("Local (localhost)");
        localBtn.setToggleGroup(modeGroup);
        localBtn.setSelected(connConfig.getMode() == ConnectionConfig.Mode.LOCAL);
        localBtn.getStyleClass().add("task-meta-label");

        RadioButton lanBtn = new RadioButton("LAN (same network)");
        lanBtn.setToggleGroup(modeGroup);
        lanBtn.setSelected(connConfig.getMode() == ConnectionConfig.Mode.LAN);
        lanBtn.getStyleClass().add("task-meta-label");

        RadioButton remoteBtn = new RadioButton("Remote (ngrok / custom)");
        remoteBtn.setToggleGroup(modeGroup);
        remoteBtn.setSelected(connConfig.getMode() == ConnectionConfig.Mode.REMOTE);
        remoteBtn.getStyleClass().add("task-meta-label");

        HBox modeRow = new HBox(20, localBtn, lanBtn, remoteBtn);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        Label hostLabel = new Label("Remote Host");
        hostLabel.getStyleClass().add("form-label");
        TextField hostField = new TextField(connConfig.getRemoteHost());
        hostField.setPromptText("e.g. 0.tcp.ngrok.io");
        hostField.setMaxWidth(400);

        Label portLabel = new Label("Remote Port");
        portLabel.getStyleClass().add("form-label");
        TextField portField = new TextField(String.valueOf(connConfig.getRemotePort()));
        portField.setPromptText("e.g. 12345");
        portField.setMaxWidth(150);

        Label lanHostLabel = new Label("LAN Host");
        lanHostLabel.getStyleClass().add("form-label");
        TextField lanHostField = new TextField(connConfig.getLanHost());
        lanHostField.setPromptText("e.g. 192.168.1.42");
        lanHostField.setMaxWidth(400);

        VBox lanFields = new VBox(8, lanHostLabel, lanHostField);
        lanFields.setVisible(connConfig.getMode() == ConnectionConfig.Mode.LAN);
        lanFields.setManaged(connConfig.getMode() == ConnectionConfig.Mode.LAN);

        VBox remoteFields = new VBox(8, hostLabel, hostField, portLabel, portField);
        remoteFields.setVisible(connConfig.getMode() == ConnectionConfig.Mode.REMOTE);
        remoteFields.setManaged(connConfig.getMode() == ConnectionConfig.Mode.REMOTE);

        Button saveBtn = new Button("Save Connection Settings");
        saveBtn.getStyleClass().add("btn-secondary");
        saveBtn.setOnAction(e -> {
            if (remoteBtn.isSelected()) {
                connConfig.setMode(ConnectionConfig.Mode.REMOTE);
                connConfig.setRemoteHost(hostField.getText().trim());
                try {
                    connConfig.setRemotePort(Integer.parseInt(portField.getText().trim()));
                } catch (NumberFormatException ex) {
                    ToastNotification.show("Invalid port number.", ToastNotification.ToastType.ERROR);
                    return;
                }
            } else if (lanBtn.isSelected()) {
                connConfig.setMode(ConnectionConfig.Mode.LAN);
                connConfig.setLanHost(lanHostField.getText().trim());
            } else {
                connConfig.setMode(ConnectionConfig.Mode.LOCAL);
            }
            ToastNotification.show("Connection settings saved. Restart the app to apply.", ToastNotification.ToastType.SUCCESS);
        });

        modeGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            boolean isRemote = newVal == remoteBtn;
            boolean isLan = newVal == lanBtn;
            remoteFields.setVisible(isRemote);
            remoteFields.setManaged(isRemote);
            lanFields.setVisible(isLan);
            lanFields.setManaged(isLan);
        });

        Label hint = new Label("For remote connections via ngrok, enter the ngrok host and port. Changes take effect after restarting the application.");
        hint.getStyleClass().add("upload-zone-subtext");
        hint.setWrapText(true);

        VBox card = new VBox(12, title, modeLabel, modeRow, lanFields, remoteFields, saveBtn, hint);
        card.getStyleClass().add("card");
        return card;
    }

    private VBox buildAccountCard() {
        ClientSession session = ClientSession.getInstance();

        Label title = new Label("ACCOUNT");
        title.getStyleClass().add("card-title");

        Label usernameLbl = new Label("Username: " + session.getUsername());
        usernameLbl.getStyleClass().add("task-title-label");

        Label roleLbl = new Label("Role: " + session.getRole());
        roleLbl.getStyleClass().add("task-meta-label");

        String email = session.getEmail();
        Label emailLbl = new Label("Email: " + (email == null || email.isBlank() ? "Not provided" : email));
        emailLbl.getStyleClass().add("task-meta-label");

        VBox card = new VBox(10, title, usernameLbl, roleLbl, emailLbl);
        card.getStyleClass().add("card");
        return card;
    }

    private VBox buildAboutCard() {
        Label title = new Label("ABOUT");
        title.getStyleClass().add("card-title");

        Label appName = new Label("DistriCompute — V2");
        appName.getStyleClass().add("task-title-label");

        Label desc = new Label("A distributed computing platform for submitting and executing JAR-based computations across multiple Slave nodes in parallel over a network. V2 features distributed task execution with Master-Slave architecture, real-time progress tracking, and multi-node fault tolerance.");
        desc.getStyleClass().add("task-meta-label");
        desc.setWrapText(true);

        VBox card = new VBox(10, title, appName, desc);
        card.getStyleClass().add("card");
        return card;
    }
}
