package com.districompute.client.components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Displays a colored circle with user initials as an avatar.
 * Color is deterministically derived from the username.
 */
public class AvatarCircle extends StackPane {

    private final Circle circle;
    private final Label initialsLabel;

    public AvatarCircle(String initials, String hexColor, double radius) {
        circle = new Circle(radius);
        circle.setFill(Color.web(hexColor));

        initialsLabel = new Label(initials);
        initialsLabel.getStyleClass().add("avatar-label");
        initialsLabel.setStyle("-fx-font-size: " + (radius * 0.6) + "px; -fx-font-weight: bold; -fx-text-fill: white;");

        setAlignment(Pos.CENTER);
        getChildren().addAll(circle, initialsLabel);
        setPrefSize(radius * 2, radius * 2);
        setMaxSize(radius * 2, radius * 2);
    }

    public void update(String initials, String hexColor) {
        initialsLabel.setText(initials);
        circle.setFill(Color.web(hexColor));
    }
}
