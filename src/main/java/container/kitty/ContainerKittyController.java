package container.kitty;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.util.Duration;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

public class ContainerKittyController {

    private static final String VERSIONS_JSON_URL =
            "https://gitlab.com/<namespace>/<repo>/-/raw/main/docker/compose/versions.json";

    private static final String DOCKER_CMD = "docker";
    private static final String COMPOSE_CMD = "compose";
    private static final String UP_CMD = "up";
    private static final String DOWN_CMD = "down";

    @FXML private Label statusLabel;
    @FXML private ComboBox<String> versionComboBox;
    @FXML private TextArea logArea;
    @FXML private TableView<ContainerInfo> containerTable;
    @FXML private TableColumn<ContainerInfo, String> nameColumn;
    @FXML private TableColumn<ContainerInfo, String> imageColumn;
    @FXML private TableColumn<ContainerInfo, String> statusColumn;

    private Timeline statusUpdater;
    private File tempComposeDir;
    private File activeComposeFile;

    @FXML
    private void handleClearLogs() {
        logArea.clear();
    }

    @FXML
    private void handleStart() {
        String version = versionComboBox.getValue();
        if (version == null || version.isEmpty()) {
            showError("No version selected.");
            return;
        }

        runCommandAsync(() -> {
            try {
                File composeFile = downloadComposeFile(version);
                if (composeFile == null) {
                    String msg = "Failed to download compose file for version: " + version;
                    appendLog("ERROR: " + msg);
                    showError(msg);
                    return;
                }

                activeComposeFile = composeFile;
                String[] cmd = {DOCKER_CMD, COMPOSE_CMD, "-f", composeFile.getAbsolutePath(), UP_CMD, "-d"};
                _runCommand(cmd);
                appendLog("Started version: " + version);

            } catch (IOException e) {
                String msg = "Error starting version: " + e.getMessage();
                appendLog("ERROR: " + msg);
                showError(msg);
            }
        });
    }

    @FXML
    private void handleStopAll() {
        runCommandAsync(() -> {
            if (activeComposeFile != null && activeComposeFile.exists()) {
                String[] cmd = {DOCKER_CMD, COMPOSE_CMD, "-f", activeComposeFile.getAbsolutePath(), DOWN_CMD};
                _runCommand(cmd);
                appendLog("Took down entire composition.");
                activeComposeFile = null;
            } else {
                String msg = "No composition is currently running; nothing to stop.";
                appendLog("ERROR: " + msg);
                showError(msg);
            }
            refreshContainers();
        });
    }

    @FXML
    private void handleRefresh() {
        runCommandAsync(() -> {
            try {
                List<String> versions = fetchAvailableVersions();
                if (versions.isEmpty()) {
                    String msg = "No versions available from server.";
                    appendLog("ERROR: " + msg);
                    showError(msg);
                    return;
                }
                Platform.runLater(() -> {
                    versionComboBox.getItems().setAll(versions);
                    versionComboBox.getSelectionModel().selectFirst();
                });
                appendLog("Refreshed versions list.");
            } catch (IOException e) {
                String msg = "Failed to fetch available versions: " + e.getMessage();
                appendLog("ERROR: " + msg);
                showError(msg);
            }

            refreshContainers();
        });
    }

    @FXML
    public void initialize() {
        // Table bindings
        nameColumn.setCellValueFactory(data -> data.getValue().nameProperty());
        imageColumn.setCellValueFactory(data -> data.getValue().imageProperty());
        statusColumn.setCellValueFactory(data -> data.getValue().statusProperty());

        statusColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(status);
                    if (status.startsWith("Up")) {
                        setStyle("-fx-text-fill: green;");
                    } else {
                        setStyle("-fx-text-fill: red;");
                    }
                }
            }
        });

        try {
            tempComposeDir = Files.createTempDirectory("docker-compose-temp").toFile();
            tempComposeDir.deleteOnExit();
        } catch (IOException e) {
            String msg = "Cannot create temporary folder for compose files: " + e.getMessage();
            appendLog("ERROR: " + msg);
            showError(msg);
        }

        refreshContainers();

        statusUpdater = new Timeline(new KeyFrame(Duration.seconds(5), event -> {
            refreshContainers();
            updateStatus();
        }));
        statusUpdater.setCycleCount(Timeline.INDEFINITE);
        statusUpdater.play();

        handleRefresh();
    }

    /** Downloads a compose file for the given version to the temp directory */
    private File downloadComposeFile(String version) throws IOException {
        // Clean old temp files
        File[] oldFiles = tempComposeDir.listFiles((d, name) -> name.endsWith(".yml"));
        if (oldFiles != null) {
            for (File f : oldFiles) f.delete();
        }

        String urlStr = VERSIONS_JSON_URL.replace("versions.json", "docker-compose-" + version + ".yml");
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        if (conn.getResponseCode() != 200) {
            appendLog("Failed to download compose file: HTTP " + conn.getResponseCode());
            return null;
        }

        File outFile = new File(tempComposeDir, "docker-compose-" + version + ".yml");
        try (InputStream in = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
        }
        return outFile;
    }

    /** Fetch available versions from versions.json in GitLab */
    private List<String> fetchAvailableVersions() throws IOException {
        URL url = new URL(VERSIONS_JSON_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        if (conn.getResponseCode() != 200) {
            throw new IOException("HTTP " + conn.getResponseCode());
        }

        try (InputStream in = conn.getInputStream()) {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(in, new TypeReference<>() {});
        }
    }

    /** Updates status label with running containers count */
    private void updateStatus() {
        long running = containerTable.getItems().stream()
                .filter(c -> c.getStatus().startsWith("Up")).count();
        long total = containerTable.getItems().size();

        if (total == 0) {
            statusLabel.setText("Status: Stopped");
            statusLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
        } else {
            statusLabel.setText("Status: " + running + "/" + total + " running");
            statusLabel.setStyle(running == total ?
                    "-fx-text-fill: green; -fx-font-weight: bold;" :
                    "-fx-text-fill: orange; -fx-font-weight: bold;");
        }
    }

    /** Refreshes the table of running containers */
    private void refreshContainers() {
        Platform.runLater(() -> containerTable.getItems().clear());

        try {
            ProcessBuilder pb = new ProcessBuilder(DOCKER_CMD, "ps", "--format", "{{.Names}}|{{.Image}}|{{.Status}}");
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\|");
                    if (parts.length == 3) {
                        ContainerInfo container = new ContainerInfo(parts[0], parts[1], parts[2]);
                        Platform.runLater(() -> containerTable.getItems().add(container));
                    }
                }
            }
        } catch (IOException e) {
            appendLog("Error fetching containers: " + e.getMessage());
        }
    }

    /** Runs a Runnable asynchronously with control disabling */
    private void runCommandAsync(Runnable task) {
        new Thread(() -> {
            setControlsDisabled(true);
            try {
                task.run();
            } finally {
                setControlsDisabled(false);
            }
        }).start();
    }

    /** Enables/disables controls on FX thread */
    private void setControlsDisabled(boolean disabled) {
        Platform.runLater(() -> {
            versionComboBox.setDisable(disabled);
            containerTable.setDisable(disabled);
        });
    }

    /** Internal command runner */
    private void _runCommand(String[] command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(line);
                }
            }

            int exitCode = process.waitFor();
            appendLog("Command exited with code: " + exitCode);

        } catch (IOException | InterruptedException e) {
            appendLog("Error: " + e.getMessage());
            showError(e.getMessage());
        }
    }

    /** Shows an error dialog */
    private void showError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
            alert.showAndWait();
        });
    }

    /** Appends a log message with timestamp */
    private void appendLog(String message) {
        String timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        Platform.runLater(() -> {
            logArea.appendText(timestamp + " - " + message + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }
}
