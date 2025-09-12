package com.example;

import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import javafx.animation.KeyFrame;
import javafx.util.Duration;

public class DockerComposeLauncherController {

    @FXML private Label statusLabel;

    private Timeline statusUpdater;
    @FXML private ComboBox<String> versionComboBox;
    @FXML private TextArea logArea;

    @FXML
    private void handleStart() {
        String version = versionComboBox.getValue();
        runCommand(new String[]{"docker", "compose", "-f", "docker-compose.yml", "up", "-d"});
        appendLog("Started version: " + version);
    }

    @FXML
    private void handleStopAll() {
        runCommand(new String[]{"docker", "compose", "-f", "docker-compose.yml", "down"});
        appendLog("Took down entire composition.");
        refreshContainers(); // update the table so it shows empty
    }

    @FXML private TableView<ContainerInfo> containerTable;
    @FXML private TableColumn<ContainerInfo, String> nameColumn;
    @FXML private TableColumn<ContainerInfo, String> imageColumn;
    @FXML private TableColumn<ContainerInfo, String> statusColumn;

    @FXML
    public void initialize() {
        // existing init
        versionComboBox.getItems().addAll("latest", "1.0.0", "1.1.0");
        versionComboBox.getSelectionModel().selectFirst();

        // table bindings setup...
        nameColumn.setCellValueFactory(data -> data.getValue().nameProperty());
        imageColumn.setCellValueFactory(data -> data.getValue().imageProperty());
        statusColumn.setCellValueFactory(data -> data.getValue().statusProperty());

        refreshContainers();

        // periodic status updater
        statusUpdater = new Timeline(
                new KeyFrame(Duration.seconds(5), event -> updateStatus())
        );
        statusUpdater.setCycleCount(Timeline.INDEFINITE);
        statusUpdater.play();
    }

    private void updateStatus() {
        if (containerTable.getItems().isEmpty()) {
            statusLabel.setText("Status: Stopped");
            statusLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
        } else {
            // Assume all containers in the composition use the same version tag
            String version = extractVersionFromImage(containerTable.getItems().get(0).imageProperty().get());
            statusLabel.setText("Status: Running — " + version);
            statusLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");
        }
    }

    private String extractVersionFromImage(String image) {
        // Example image: registry.gitlab.com/project/app:1.2.3
        int colonIndex = image.lastIndexOf(":");
        if (colonIndex != -1 && colonIndex < image.length() - 1) {
            return image.substring(colonIndex + 1);
        }
        return "unknown";
    }

    @FXML
    private void handleRefresh() {
        appendLog("Refreshing versions and containers...");
        refreshContainers();
    }

    // Query Docker to list running containers
    private void refreshContainers() {
        containerTable.getItems().clear();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "ps", "--format", "{{.Names}}|{{.Image}}|{{.Status}}");
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\|");
                    if (parts.length == 3) {
                        containerTable.getItems().add(
                                new ContainerInfo(parts[0], parts[1], parts[2]));
                    }
                }
            }
        } catch (IOException e) {
            appendLog("Error fetching containers: " + e.getMessage());
        }
    }

    private void runCommand(String[] command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(line);
                }
            }

            int exitCode = process.waitFor();
            appendLog("Command exited with code: " + exitCode);
        } catch (IOException | InterruptedException e) {
            appendLog("Error: " + e.getMessage());
        }
    }

    private void appendLog(String message) {
        logArea.appendText(message + "\n");
    }
}
