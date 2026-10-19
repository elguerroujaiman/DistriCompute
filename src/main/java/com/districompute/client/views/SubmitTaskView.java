package com.districompute.client.views;

import com.districompute.client.ClientSession;
import com.districompute.client.components.ToastNotification;
import com.districompute.common.models.NetworkMessage;
import com.districompute.common.models.NetworkMessage.Type;
import com.districompute.network.ServerConnection;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.*;

/**
 * Submit Task view (V2) — with multi-tab system for batch submission.
 * Supports up to 5 tabs. Each tab has its own JAR uploads and form fields.
 * Includes "Submit Current" and "Submit All" buttons.
 * New fields: Assigned To (user dropdown), Deadline (date picker).
 */
public class SubmitTaskView extends VBox {

    private final ServerConnection connection;
    private final Runnable onSubmitSuccess;

    private static final int MAX_TABS = 5;
    private static File lastBrowseDirectory;

    // Tab bar
    private HBox tabBar;
    private Button addTabBtn;
    private Button closeAllBtn;

    // Tab data
    private final List<TaskTabData> tabs = new ArrayList<>();
    private int activeTabIndex = 0;

    // Content area for the active tab
    private VBox tabContent;

    // Submit buttons
    private Button submitCurrentBtn;
    private Button submitAllBtn;

    // User list cache for assignment dropdown
    private List<Map<String, String>> userListCache = new ArrayList<>();

    public SubmitTaskView(ServerConnection connection, Runnable onSubmitSuccess) {
        this.connection = connection;
        this.onSubmitSuccess = onSubmitSuccess;
        buildUI();
        addTab(); // Tab 1 is always present
        refreshUserList();
    }

    private void buildUI() {
        getStyleClass().add("content-area");
        setSpacing(24);
        setMaxWidth(720);

        Label title = new Label("Submit Task");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Upload your Task JAR and Splitter JAR, configure, and distribute");
        subtitle.getStyleClass().add("page-subtitle");
        VBox header = new VBox(4, title, subtitle);

        // Tab bar
        tabBar = new HBox(4);
        tabBar.setAlignment(Pos.CENTER_LEFT);

        addTabBtn = new Button("+");
        addTabBtn.getStyleClass().add("btn-secondary");
        addTabBtn.setStyle("-fx-min-width: 32; -fx-min-height: 32; -fx-padding: 4 8;");
        addTabBtn.setTooltip(new Tooltip("Add new task tab (max " + MAX_TABS + ")"));
        addTabBtn.setOnAction(e -> addTab());

        closeAllBtn = new Button("Close All Extra");
        closeAllBtn.getStyleClass().add("btn-secondary");
        closeAllBtn.setStyle("-fx-font-size: 11px;");
        closeAllBtn.setVisible(false);
        closeAllBtn.setManaged(false);
        closeAllBtn.setOnAction(e -> closeAllExtraTabs());

        HBox tabBarContainer = new HBox(8, tabBar, addTabBtn, closeAllBtn);
        tabBarContainer.setAlignment(Pos.CENTER_LEFT);

        // Tab content
        tabContent = new VBox(24);

        // Submit buttons
        submitCurrentBtn = new Button("Submit Current");
        submitCurrentBtn.getStyleClass().add("btn-primary");
        submitCurrentBtn.setPrefWidth(160);
        submitCurrentBtn.setOnAction(e -> handleSubmitCurrent());

        submitAllBtn = new Button("Submit All");
        submitAllBtn.getStyleClass().add("btn-primary");
        submitAllBtn.setPrefWidth(160);
        submitAllBtn.setOnAction(e -> handleSubmitAll());

        HBox submitRow = new HBox(12, submitCurrentBtn, submitAllBtn);
        submitRow.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(header, tabBarContainer, tabContent, submitRow);
    }

    // -------------------------------------------------------------------------
    // Tab management
    // -------------------------------------------------------------------------

    private void addTab() {
        if (tabs.size() >= MAX_TABS) {
            ToastNotification.show("Maximum " + MAX_TABS + " tabs allowed.", ToastNotification.ToastType.WARNING);
            return;
        }

        TaskTabData tabData = new TaskTabData(tabs.size() + 1);
        tabs.add(tabData);
        rebuildTabBar();
        switchToTab(tabs.size() - 1);
    }

    private void closeTab(int index) {
        if (index == 0) return; // Tab 1 cannot be closed
        tabs.remove(index);
        // Renumber remaining tabs
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).tabNumber = i + 1;
        }
        if (activeTabIndex >= tabs.size()) activeTabIndex = tabs.size() - 1;
        rebuildTabBar();
        switchToTab(activeTabIndex);
    }

    private void closeAllExtraTabs() {
        while (tabs.size() > 1) {
            tabs.remove(tabs.size() - 1);
        }
        activeTabIndex = 0;
        rebuildTabBar();
        switchToTab(0);
    }

    private void rebuildTabBar() {
        tabBar.getChildren().clear();
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            TaskTabData tab = tabs.get(i);

            HBox tabButton = new HBox(4);
            tabButton.setAlignment(Pos.CENTER);
            tabButton.setPadding(new Insets(6, 12, 6, 12));
            tabButton.setStyle(i == activeTabIndex ?
                    "-fx-background-color: -dc-accent; -fx-background-radius: 6; -fx-cursor: hand;" :
                    "-fx-background-color: -dc-bg-card; -fx-background-radius: 6; -fx-border-color: -dc-border; -fx-border-radius: 6; -fx-cursor: hand;");

            Label tabLabel = new Label("Task " + tab.tabNumber);
            tabLabel.setStyle(i == activeTabIndex ?
                    "-fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold;" :
                    "-fx-text-fill: -dc-text-secondary; -fx-font-size: 12px;");
            tabButton.getChildren().add(tabLabel);

            if (i > 0) { // Close button for non-first tabs
                Label closeLabel = new Label("×");
                closeLabel.setStyle("-fx-text-fill: " + (i == activeTabIndex ? "white" : "-dc-text-muted") +
                        "; -fx-font-size: 14px; -fx-cursor: hand; -fx-padding: 0 0 0 4;");
                closeLabel.setOnMouseClicked(e -> { e.consume(); closeTab(idx); });
                tabButton.getChildren().add(closeLabel);
            }

            tabButton.setOnMouseClicked(e -> switchToTab(idx));
            tabBar.getChildren().add(tabButton);
        }

        boolean hasExtra = tabs.size() > 1;
        closeAllBtn.setVisible(hasExtra);
        closeAllBtn.setManaged(hasExtra);
        addTabBtn.setDisable(tabs.size() >= MAX_TABS);
    }

    private void switchToTab(int index) {
        // Save current tab state
        if (activeTabIndex < tabs.size()) {
            saveCurrentTabState();
        }

        activeTabIndex = index;
        rebuildTabBar();
        renderActiveTab();
    }

    private void saveCurrentTabState() {
        // State is stored directly in TaskTabData via references — no extra save needed
    }

    private void renderActiveTab() {
        tabContent.getChildren().clear();
        if (activeTabIndex >= tabs.size()) return;

        TaskTabData tab = tabs.get(activeTabIndex);

        VBox uploadSection = buildUploadSection(tab);
        VBox formSection = buildFormSection(tab);
        VBox previewSection = buildPreviewSection(tab);

        tabContent.getChildren().addAll(uploadSection, formSection, previewSection);
    }

    // -------------------------------------------------------------------------
    // Upload section
    // -------------------------------------------------------------------------

    private VBox buildUploadSection(TaskTabData tab) {
        Label sectionTitle = new Label("JAR FILES");
        sectionTitle.getStyleClass().add("card-title");

        // Task JAR
        Label taskTitle = new Label("Task JAR");
        taskTitle.getStyleClass().add("form-label");
        Label taskHint = new Label("Your main computation JAR");
        taskHint.getStyleClass().add("upload-zone-subtext");

        VBox taskUploadZone = new VBox(10);
        taskUploadZone.getStyleClass().add("upload-zone");
        taskUploadZone.setAlignment(Pos.CENTER);
        taskUploadZone.setMinHeight(110);

        FontIcon taskIcon = new FontIcon("fas-cogs");
        taskIcon.setIconSize(28);
        taskIcon.setIconColor(javafx.scene.paint.Color.web("#4f8ef7"));
        Label taskDropText = new Label("Drop Task JAR here or click to browse");
        taskDropText.getStyleClass().add("upload-zone-text");
        taskUploadZone.getChildren().addAll(taskIcon, taskDropText);
        taskUploadZone.setOnMouseClicked(e -> browseJar(tab, true));
        setupDragDrop(taskUploadZone, tab, true);

        Label taskFileLabel = new Label(tab.taskJarFile != null ?
                "✓ " + tab.taskJarFile.getName() + " (" + formatFileSize(tab.taskJarFile.length()) + ")" :
                "No file selected");
        taskFileLabel.getStyleClass().add("upload-zone-subtext");
        if (tab.taskJarFile != null) taskFileLabel.setStyle("-fx-text-fill: #34d399; -fx-font-size: 11px;");
        tab.taskFileLabel = taskFileLabel;

        VBox taskSection = new VBox(6, taskTitle, taskHint, taskUploadZone, taskFileLabel);

        // Splitter JAR
        Label splitterTitle = new Label("Splitter JAR");
        splitterTitle.getStyleClass().add("form-label");
        Label splitterHint = new Label("Divides your task across Slaves (prints one range per line)");
        splitterHint.getStyleClass().add("upload-zone-subtext");

        VBox splitterUploadZone = new VBox(10);
        splitterUploadZone.getStyleClass().add("upload-zone");
        splitterUploadZone.setAlignment(Pos.CENTER);
        splitterUploadZone.setMinHeight(110);

        FontIcon splitterIcon = new FontIcon("fas-cut");
        splitterIcon.setIconSize(28);
        splitterIcon.setIconColor(javafx.scene.paint.Color.web("#4f8ef7"));
        Label splitterDropText = new Label("Drop Splitter JAR here or click to browse");
        splitterDropText.getStyleClass().add("upload-zone-text");
        splitterUploadZone.getChildren().addAll(splitterIcon, splitterDropText);
        splitterUploadZone.setOnMouseClicked(e -> browseJar(tab, false));
        setupDragDrop(splitterUploadZone, tab, false);

        Label splitterFileLabel = new Label(tab.splitterJarFile != null ?
                "✓ " + tab.splitterJarFile.getName() + " (" + formatFileSize(tab.splitterJarFile.length()) + ")" :
                "No file selected");
        splitterFileLabel.getStyleClass().add("upload-zone-subtext");
        if (tab.splitterJarFile != null) splitterFileLabel.setStyle("-fx-text-fill: #34d399; -fx-font-size: 11px;");
        tab.splitterFileLabel = splitterFileLabel;

        VBox splitterSection = new VBox(6, splitterTitle, splitterHint, splitterUploadZone, splitterFileLabel);

        HBox uploadRow = new HBox(16, taskSection, splitterSection);
        HBox.setHgrow(taskSection, Priority.ALWAYS);
        HBox.setHgrow(splitterSection, Priority.ALWAYS);

        VBox section = new VBox(12, sectionTitle, uploadRow);
        section.getStyleClass().add("card");
        return section;
    }

    private void setupDragDrop(VBox zone, TaskTabData tab, boolean isTaskJar) {
        zone.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                zone.getStyleClass().add("upload-zone-active");
            }
            e.consume();
        });
        zone.setOnDragExited(e -> zone.getStyleClass().remove("upload-zone-active"));
        zone.setOnDragDropped(e -> {
            List<File> files = e.getDragboard().getFiles();
            if (!files.isEmpty()) {
                File f = files.get(0);
                if (f.getName().toLowerCase().endsWith(".jar")) {
                    setJarFile(tab, f, isTaskJar);
                } else {
                    ToastNotification.show("Only .jar files are accepted.", ToastNotification.ToastType.WARNING);
                }
            }
            e.setDropCompleted(true);
            e.consume();
        });
    }

    // -------------------------------------------------------------------------
    // Form section
    // -------------------------------------------------------------------------

    private VBox buildFormSection(TaskTabData tab) {
        Label sectionTitle = new Label("TASK DETAILS");
        sectionTitle.getStyleClass().add("card-title");

        Label titleLbl = new Label("Title *");
        titleLbl.getStyleClass().add("form-label");
        tab.titleField = new TextField(tab.title);
        tab.titleField.setPromptText("A descriptive name for this computation");
        tab.titleField.setMaxWidth(Double.MAX_VALUE);
        tab.titleField.textProperty().addListener((obs, o, n) -> tab.title = n);

        Label descLbl = new Label("Description");
        descLbl.getStyleClass().add("form-label");
        tab.descriptionField = new TextArea(tab.description);
        tab.descriptionField.setPromptText("What does this task compute? (optional)");
        tab.descriptionField.setPrefRowCount(3);
        tab.descriptionField.setMaxWidth(Double.MAX_VALUE);
        tab.descriptionField.setWrapText(true);
        tab.descriptionField.textProperty().addListener((obs, o, n) -> tab.description = n);

        // Assigned To
        Label assignedLbl = new Label("Assigned To");
        assignedLbl.getStyleClass().add("form-label");
        tab.assignedToCombo = new ComboBox<>();
        tab.assignedToCombo.setMaxWidth(Double.MAX_VALUE);
        tab.assignedToCombo.setPromptText("Select user (defaults to you)");
        populateUserCombo(tab.assignedToCombo, tab.assignedToUserId);
        // Auto-refresh on click
        tab.assignedToCombo.setOnShowing(e -> refreshUserListAndPopulate(tab.assignedToCombo, tab.assignedToUserId));
        tab.assignedToCombo.setOnAction(e -> {
            String selected = tab.assignedToCombo.getValue();
            if (selected != null) {
                for (Map<String, String> u : userListCache) {
                    if (selected.equals(u.get("username"))) {
                        tab.assignedToUserId = u.get("id");
                        tab.assignedToUsername = u.get("username");
                        break;
                    }
                }
            }
        });
        Label assignedHint = new Label("Who this task is assigned to. Defaults to yourself.");
        assignedHint.getStyleClass().add("upload-zone-subtext");

        // Deadline
        Label deadlineLbl = new Label("Deadline (optional)");
        deadlineLbl.getStyleClass().add("form-label");
        tab.deadlinePicker = new DatePicker(tab.deadline);
        tab.deadlinePicker.setMaxWidth(Double.MAX_VALUE);
        tab.deadlinePicker.setPromptText("Select deadline date");
        tab.deadlinePicker.valueProperty().addListener((obs, o, n) -> tab.deadline = n);

        Label priorityLbl = new Label("Priority");
        priorityLbl.getStyleClass().add("form-label");
        tab.priorityCombo = new ComboBox<>();
        tab.priorityCombo.getItems().addAll("HIGH", "MEDIUM", "LOW");
        tab.priorityCombo.setValue(tab.priority);
        tab.priorityCombo.setMaxWidth(Double.MAX_VALUE);
        tab.priorityCombo.setOnAction(e -> tab.priority = tab.priorityCombo.getValue());

        Label priorityHint = new Label("HIGH priority tasks are processed first in the queue.");
        priorityHint.getStyleClass().add("upload-zone-subtext");

        Label slaveLbl = new Label("Number of Slaves");
        slaveLbl.getStyleClass().add("form-label");
        tab.slaveCountSpinner = new Spinner<>(1, 3, tab.slaveCount);
        tab.slaveCountSpinner.setEditable(false);
        tab.slaveCountSpinner.setPrefWidth(80);
        tab.slaveCountSpinner.valueProperty().addListener((obs, o, n) -> {
            tab.slaveCount = n;
            updateSplitPreview(tab);
        });

        Label slaveHint = new Label("How many Slave nodes will process your task in parallel (max 3).");
        slaveHint.getStyleClass().add("upload-zone-subtext");
        HBox slaveRow = new HBox(10, tab.slaveCountSpinner, slaveHint);
        slaveRow.setAlignment(Pos.CENTER_LEFT);

        VBox section = new VBox(10,
                sectionTitle,
                titleLbl, tab.titleField,
                descLbl, tab.descriptionField,
                assignedLbl, tab.assignedToCombo, assignedHint,
                deadlineLbl, tab.deadlinePicker,
                priorityLbl, tab.priorityCombo, priorityHint,
                slaveLbl, slaveRow
        );
        section.getStyleClass().add("card");
        return section;
    }

    // -------------------------------------------------------------------------
    // Preview section
    // -------------------------------------------------------------------------

    private VBox buildPreviewSection(TaskTabData tab) {
        Label sectionTitle = new Label("DISTRIBUTION PREVIEW");
        sectionTitle.getStyleClass().add("card-title");

        Label hint = new Label("Upload both JARs to see how your task will be divided across Slaves.");
        hint.getStyleClass().add("upload-zone-subtext");
        hint.setWrapText(true);

        tab.splitPreviewBox = new VBox(8, sectionTitle, hint);
        tab.splitPreviewBox.getStyleClass().add("card");

        if (tab.taskJarFile != null && tab.splitterJarFile != null) {
            updateSplitPreview(tab);
        }

        return tab.splitPreviewBox;
    }

    private void updateSplitPreview(TaskTabData tab) {
        if (tab.taskJarFile == null || tab.splitterJarFile == null || tab.splitPreviewBox == null) return;
        int slaves = tab.slaveCount;

        Thread t = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "java", "-jar", tab.splitterJarFile.getAbsolutePath(),
                        String.valueOf(slaves));
                pb.redirectErrorStream(true);
                Process p = pb.start();

                List<String> lines = new ArrayList<>();
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isBlank()) lines.add(line);
                    }
                }
                p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);

                String[] slaveNames = {"Mercury", "Venus", "Mars"};
                String[] slaveColors = {"#94a3b8", "#fbbf24", "#f87171"};

                Platform.runLater(() -> {
                    tab.splitPreviewBox.getChildren().clear();
                    Label title = new Label("DISTRIBUTION PREVIEW");
                    title.getStyleClass().add("card-title");
                    tab.splitPreviewBox.getChildren().add(title);

                    if (lines.isEmpty()) {
                        Label err = new Label("Splitter JAR produced no output. Check your Splitter JAR.");
                        err.setStyle("-fx-text-fill: #f87171;");
                        tab.splitPreviewBox.getChildren().add(err);
                    } else if (lines.size() != slaves) {
                        Label warn = new Label("Splitter produced " + lines.size() +
                                " segments but " + slaves + " Slaves selected.");
                        warn.setStyle("-fx-text-fill: #fbbf24;");
                        tab.splitPreviewBox.getChildren().add(warn);
                    } else {
                        Label ok = new Label("Splitter validated — " + slaves + " segment(s) ready:");
                        ok.setStyle("-fx-text-fill: #34d399; -fx-font-weight: bold;");
                        tab.splitPreviewBox.getChildren().add(ok);

                        for (int i = 0; i < lines.size(); i++) {
                            String name = i < slaveNames.length ? slaveNames[i] : "Slave-" + i;
                            String color = i < slaveColors.length ? slaveColors[i] : "#6366f1";
                            Label nameLbl = new Label(name);
                            nameLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: " + color +
                                    "; -fx-font-size: 12px; -fx-min-width: 70;");
                            Label arrow = new Label("→");
                            arrow.getStyleClass().add("upload-zone-subtext");
                            Label argsLbl = new Label("args: [" + lines.get(i) + "]");
                            argsLbl.getStyleClass().add("upload-zone-subtext");
                            HBox row = new HBox(8, nameLbl, arrow, argsLbl);
                            row.setAlignment(Pos.CENTER_LEFT);
                            tab.splitPreviewBox.getChildren().add(row);
                        }
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    tab.splitPreviewBox.getChildren().clear();
                    Label title = new Label("DISTRIBUTION PREVIEW");
                    title.getStyleClass().add("card-title");
                    Label err = new Label("Could not run Splitter JAR: " + e.getMessage());
                    err.setStyle("-fx-text-fill: #f87171;");
                    tab.splitPreviewBox.getChildren().addAll(title, err);
                });
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // File handling
    // -------------------------------------------------------------------------

    private void browseJar(TaskTabData tab, boolean isTaskJar) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(isTaskJar ? "Select Task JAR" : "Select Splitter JAR");
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("JAR Files", "*.jar"));
        if (lastBrowseDirectory != null && lastBrowseDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastBrowseDirectory);
        }
        File f = chooser.showOpenDialog(getScene().getWindow());
        if (f != null) {
            lastBrowseDirectory = f.getParentFile();
            setJarFile(tab, f, isTaskJar);
        }
    }

    private void setJarFile(TaskTabData tab, File file, boolean isTaskJar) {
        String sizeStr = formatFileSize(file.length());
        if (isTaskJar) {
            tab.taskJarFile = file;
            if (tab.taskFileLabel != null) {
                tab.taskFileLabel.setText("✓ " + file.getName() + " (" + sizeStr + ")");
                tab.taskFileLabel.setStyle("-fx-text-fill: #34d399; -fx-font-size: 11px;");
            }
        } else {
            tab.splitterJarFile = file;
            if (tab.splitterFileLabel != null) {
                tab.splitterFileLabel.setText("✓ " + file.getName() + " (" + sizeStr + ")");
                tab.splitterFileLabel.setStyle("-fx-text-fill: #34d399; -fx-font-size: 11px;");
            }
        }
        if (tab.taskJarFile != null && tab.splitterJarFile != null) updateSplitPreview(tab);
    }

    // -------------------------------------------------------------------------
    // User list for assignment
    // -------------------------------------------------------------------------

    private void refreshUserList() {
        Thread t = new Thread(() -> {
            NetworkMessage resp = connection.sendRequest(NetworkMessage.of(Type.GET_USER_LIST));
            if (resp.isSuccess() && resp.getPayload() instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> users = (List<Map<String, String>>) list;
                Platform.runLater(() -> userListCache = users);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void refreshUserListAndPopulate(ComboBox<String> combo, String selectedUserId) {
        Thread t = new Thread(() -> {
            NetworkMessage resp = connection.sendRequest(NetworkMessage.of(Type.GET_USER_LIST));
            if (resp.isSuccess() && resp.getPayload() instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> users = (List<Map<String, String>>) list;
                Platform.runLater(() -> {
                    userListCache = users;
                    populateUserCombo(combo, selectedUserId);
                });
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void populateUserCombo(ComboBox<String> combo, String selectedUserId) {
        String currentValue = combo.getValue();
        combo.getItems().clear();
        String currentUsername = ClientSession.getInstance().getUsername();
        combo.getItems().add(currentUsername + " (you)");

        for (Map<String, String> u : userListCache) {
            String uname = u.get("username");
            if (!uname.equals(currentUsername)) {
                combo.getItems().add(uname);
            }
        }

        if (selectedUserId != null && !selectedUserId.isEmpty()) {
            for (Map<String, String> u : userListCache) {
                if (u.get("id").equals(selectedUserId)) {
                    String uname = u.get("username");
                    if (uname.equals(currentUsername)) {
                        combo.setValue(currentUsername + " (you)");
                    } else {
                        combo.setValue(uname);
                    }
                    break;
                }
            }
        } else {
            combo.setValue(currentUsername + " (you)");
        }
    }

    // -------------------------------------------------------------------------
    // Submit
    // -------------------------------------------------------------------------

    private void handleSubmitCurrent() {
        TaskTabData tab = tabs.get(activeTabIndex);
        if (!isTabReady(tab)) {
            ToastNotification.show("Please fill in all required fields and upload both JARs.", ToastNotification.ToastType.WARNING);
            return;
        }

        submitCurrentBtn.setDisable(true);
        submitCurrentBtn.setText("Submitting...");

        Thread t = new Thread(() -> {
            Map<String, Object> payload = buildPayload(tab);
            NetworkMessage response = connection.sendRequest(new NetworkMessage(Type.SUBMIT_TASK, payload));

            Platform.runLater(() -> {
                submitCurrentBtn.setDisable(false);
                submitCurrentBtn.setText("Submit Current");

                if (response != null && response.isSuccess()) {
                    ToastNotification.show("Task submitted! Distributing to Slaves...",
                            ToastNotification.ToastType.SUCCESS);

                    // Close this tab
                    if (tabs.size() > 1) {
                        closeTab(activeTabIndex);
                    } else {
                        resetTab(tab);
                        renderActiveTab();
                    }
                    if (onSubmitSuccess != null) onSubmitSuccess.run();
                } else {
                    String msg = response != null ? response.getMessage() : "No response from server.";
                    ToastNotification.show(msg, ToastNotification.ToastType.ERROR);
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private void handleSubmitAll() {
        List<TaskTabData> readyTabs = new ArrayList<>();
        for (TaskTabData tab : tabs) {
            if (isTabReady(tab)) readyTabs.add(tab);
        }

        if (readyTabs.isEmpty()) {
            ToastNotification.show("No tabs have all required fields filled.", ToastNotification.ToastType.WARNING);
            return;
        }

        submitAllBtn.setDisable(true);
        submitAllBtn.setText("Submitting " + readyTabs.size() + " tasks...");

        Thread t = new Thread(() -> {
            List<Map<String, Object>> payloads = new ArrayList<>();
            for (TaskTabData tab : readyTabs) {
                payloads.add(buildPayload(tab));
            }

            NetworkMessage response = connection.sendRequest(new NetworkMessage(Type.SUBMIT_BATCH, payloads));

            Platform.runLater(() -> {
                submitAllBtn.setDisable(false);
                submitAllBtn.setText("Submit All");

                if (response != null && response.isSuccess()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) response.getPayload();
                    int submitted = ((Number) result.getOrDefault("totalSubmitted", 0)).intValue();
                    int failed = ((Number) result.getOrDefault("totalFailed", 0)).intValue();

                    String msg = submitted + " task(s) submitted";
                    if (failed > 0) msg += ", " + failed + " failed";
                    ToastNotification.show(msg + "!",
                            failed > 0 ? ToastNotification.ToastType.WARNING : ToastNotification.ToastType.SUCCESS);

                    // Reset all tabs
                    closeAllExtraTabs();
                    resetTab(tabs.get(0));
                    renderActiveTab();

                    if (onSubmitSuccess != null) onSubmitSuccess.run();
                } else {
                    String msg = response != null ? response.getMessage() : "No response from server.";
                    ToastNotification.show(msg, ToastNotification.ToastType.ERROR);
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private boolean isTabReady(TaskTabData tab) {
        return tab.taskJarFile != null
                && tab.splitterJarFile != null
                && tab.title != null
                && !tab.title.isBlank();
    }

    private Map<String, Object> buildPayload(TaskTabData tab) {
        Map<String, Object> payload = new LinkedHashMap<>();
        try {
            byte[] taskBytes = Files.readAllBytes(tab.taskJarFile.toPath());
            byte[] splitterBytes = Files.readAllBytes(tab.splitterJarFile.toPath());

            payload.put("title", tab.title);
            payload.put("description", tab.description);
            payload.put("priority", tab.priority);
            payload.put("jarFileName", tab.taskJarFile.getName());
            payload.put("jarData", Base64.getEncoder().encodeToString(taskBytes));
            payload.put("splitterFileName", tab.splitterJarFile.getName());
            payload.put("splitterData", Base64.getEncoder().encodeToString(splitterBytes));
            payload.put("requestedSlaves", tab.slaveCount);
            payload.put("assignedToUserId", tab.assignedToUserId);
            payload.put("assignedToUsername", tab.assignedToUsername);
            if (tab.deadline != null) {
                payload.put("deadline", tab.deadline.toString());
            }
        } catch (IOException ex) {
            ToastNotification.show("Failed to read JAR files: " + ex.getMessage(), ToastNotification.ToastType.ERROR);
        }
        return payload;
    }

    private void resetTab(TaskTabData tab) {
        tab.taskJarFile = null;
        tab.splitterJarFile = null;
        tab.title = "";
        tab.description = "";
        tab.priority = "MEDIUM";
        tab.slaveCount = 1;
        tab.deadline = null;
        tab.assignedToUserId = ClientSession.getInstance().getUserId();
        tab.assignedToUsername = ClientSession.getInstance().getUsername();
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    public void setupListeners() {
        // No longer needed as listeners are set up per tab
    }

    /**
     * Called when navigating back to this view — switch to lowest available tab.
     */
    public void focusNextAvailableTab() {
        if (!tabs.isEmpty()) {
            switchToTab(0);
        }
    }

    // -------------------------------------------------------------------------
    // Tab data holder
    // -------------------------------------------------------------------------

    private static class TaskTabData {
        int tabNumber;
        File taskJarFile;
        File splitterJarFile;
        String title = "";
        String description = "";
        String priority = "MEDIUM";
        int slaveCount = 1;
        LocalDate deadline;
        String assignedToUserId;
        String assignedToUsername;

        // UI references (rebuilt each time tab is rendered)
        TextField titleField;
        TextArea descriptionField;
        ComboBox<String> priorityCombo;
        ComboBox<String> assignedToCombo;
        DatePicker deadlinePicker;
        Spinner<Integer> slaveCountSpinner;
        Label taskFileLabel;
        Label splitterFileLabel;
        VBox splitPreviewBox;

        TaskTabData(int num) {
            this.tabNumber = num;
            this.assignedToUserId = ClientSession.getInstance().getUserId();
            this.assignedToUsername = ClientSession.getInstance().getUsername();
        }
    }
}
