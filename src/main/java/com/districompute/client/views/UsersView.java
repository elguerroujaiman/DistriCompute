package com.districompute.client.views;

import com.districompute.client.components.AvatarCircle;
import com.districompute.client.components.ToastNotification;
import com.districompute.common.models.NetworkMessage;
import com.districompute.common.models.NetworkMessage.Type;
import com.districompute.network.ServerConnection;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.*;

/**
 * Users view — Admin only. Shows all registered users with refresh button.
 */
public class UsersView extends VBox {

    private final ServerConnection connection;
    private VBox userList;

    public UsersView(ServerConnection connection) {
        this.connection = connection;
        buildUI();
        refresh();
    }

    private void buildUI() {
        getStyleClass().add("content-area");
        setSpacing(20);

        Label title = new Label("User Management");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("View and manage all registered users");
        subtitle.getStyleClass().add("page-subtitle");
        VBox header = new VBox(4, title, subtitle);

        Label listTitle = new Label("ALL USERS");
        listTitle.getStyleClass().add("card-title");

        userList = new VBox(8);

        Label loading = new Label("Loading...");
        loading.getStyleClass().add("task-meta-label");
        loading.setPadding(new Insets(12));
        userList.getChildren().add(loading);

        FontIcon refreshIcon = new FontIcon("fas-sync-alt");
        refreshIcon.setIconSize(13);
        refreshIcon.setIconColor(javafx.scene.paint.Color.web("#8b90a0"));

        Button refreshBtn = new Button("Refresh");
        refreshBtn.setGraphic(refreshIcon);
        refreshBtn.getStyleClass().add("btn-secondary");
        refreshBtn.setOnAction(e -> refresh());
        HBox refreshRow = new HBox(refreshBtn);
        refreshRow.setAlignment(Pos.CENTER_RIGHT);

        VBox card = new VBox(10, listTitle, userList);
        card.getStyleClass().add("card");

        getChildren().addAll(header, refreshRow, card);
    }

    public void refresh() {
        Thread t = new Thread(() -> {
            NetworkMessage resp = connection.sendRequest(NetworkMessage.of(Type.GET_ALL_USERS));
            Platform.runLater(() -> {
                userList.getChildren().clear();
                if (resp.isSuccess() && resp.getPayload() instanceof List<?> list) {
                    for (Object obj : list) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> user = (Map<String, Object>) obj;
                        userList.getChildren().add(buildUserRow(user));
                    }
                } else {
                    Label err = new Label("Failed to load users: " + resp.getMessage());
                    err.getStyleClass().add("error-label");
                    userList.getChildren().add(err);
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private HBox buildUserRow(Map<String, Object> user) {
        String username = (String) user.getOrDefault("username", "?");
        String email = (String) user.getOrDefault("email", "");
        String role = (String) user.getOrDefault("role", "STANDARD");
        boolean active = Boolean.parseBoolean(String.valueOf(user.getOrDefault("active", "true")));

        String[] colors = {"#4f8ef7","#7c5cbf","#2dd69b","#f5a623","#f5564a","#00bcd4","#e91e8c","#8bc34a"};
        String color = colors[Math.abs(username.hashCode()) % colors.length];
        String initials = username.substring(0, Math.min(2, username.length())).toUpperCase();

        AvatarCircle avatar = new AvatarCircle(initials, color, 18);

        Label usernameLbl = new Label(username);
        usernameLbl.getStyleClass().add("task-title-label");
        usernameLbl.setMinWidth(140);

        Label emailLbl = new Label(email.isBlank() ? "—" : email);
        emailLbl.getStyleClass().add("task-meta-label");
        emailLbl.setMinWidth(180);
        HBox.setHgrow(emailLbl, Priority.ALWAYS);

        Label roleBadge = new Label(role);
        roleBadge.getStyleClass().addAll("role-badge", role.toLowerCase());

        boolean online = Boolean.parseBoolean(String.valueOf(user.getOrDefault("online", "false")));
        Label activeBadge = new Label(online ? "Online" : "Offline");
        activeBadge.getStyleClass().addAll("status-badge", online ? "status-completed" : "status-cancelled");

        HBox row = new HBox(12, avatar, usernameLbl, emailLbl, roleBadge, activeBadge);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 16, 10, 16));
        row.setStyle("-fx-background-color: -dc-bg-card; -fx-background-radius: 8; -fx-border-color: -dc-border; -fx-border-radius: 8; -fx-border-width: 1;");
        return row;
    }
}
