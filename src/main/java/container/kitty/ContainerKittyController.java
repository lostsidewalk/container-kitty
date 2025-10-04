package container.kitty;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ContainerKittyController
 */
@SuppressWarnings({
        "AccessOfSystemProperties",
        "ClassWithoutLogger",
        "FieldNotUsedInToString",
        "FieldCanBeLocal",
        "MagicNumber",
        "UseOfProcessBuilder",
        "OverlyLongMethod",
        "StaticFieldReferencedViaSubclass",
        "StandardVariableNames"
})
public class ContainerKittyController {

    // Remote URL for production
    private static final String VERSIONS_JSON_URL =
            "https://gitlab.com/<namespace>/<repo>/-/raw/main/docker/compose/versions.json";

    private static final String DOCKER_CMD = "docker";
    private static final String COMPOSE_CMD = "compose";
    private static final String UP_CMD = "up";
    private static final String DOWN_CMD = "down";

    @FXML private Label statusLabel;
    @FXML private TextArea logArea;
    @FXML private TableView<ContainerInfo> containerTable;
    @FXML private TableColumn<ContainerInfo, String> nameColumn;
    @FXML private TableColumn<ContainerInfo, String> imageColumn;
    @FXML private TableColumn<ContainerInfo, String> memUsageColumn;
    @FXML private TableColumn<ContainerInfo, String> statusColumn;
    @FXML private Button startButton;
    @FXML private Button stopAllButton;
    @FXML private TableView<CompositionVersion> compositionVersionTable;
    @FXML private TableColumn<CompositionVersion, String> compositionColumn;
    @FXML private TableColumn<CompositionVersion, String> versionColumn;
    @FXML private TableColumn<CompositionVersion, String> commentColumn;

    // local cache of lists
    private List<Composition> availableCompositions = List.of();
    private List<Version> availableVersions = List.of();
    private Timeline statusUpdater;
    private File tempComposeDir;
    private File activeComposeFile;

    @FXML
    private void handleAbout() {
        String tempDirPath = (tempComposeDir != null) ? tempComposeDir.getAbsolutePath() : "Not initialized";
        String dockerPath = detectDockerPath();

        String content = """
        Container Kitty Launcher
        Version: 1.0.0
        Author: Your Team
        Built with JavaFX

        Java Runtime: %s
        JavaFX Runtime: %s
        OS: %s %s (%s)
        Docker Executable: %s

        Docker Compose Versions JSON:
        %s

        Temporary Compose Directory:
        %s
        """.formatted(
                System.getProperty("java.version"),
                System.getProperty("javafx.runtime.version", "Unknown"),
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"),
                dockerPath,
                VERSIONS_JSON_URL,
                tempDirPath
        );

        TextArea textArea = new TextArea(content);
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12;");
        textArea.setPrefWidth(500);
        textArea.setPrefHeight(300);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About Container Kitty");
        alert.setHeaderText("Container Kitty Launcher");
        alert.getDialogPane().setContent(textArea);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        // Load your logo from resources
        try {
            InputStream logoStream = getClass().getResourceAsStream("/container/kitty/logo.png");
            if (logoStream != null) {
                javafx.scene.image.Image logo = new javafx.scene.image.Image(logoStream);
                ImageView imageView = new ImageView(logo);
                imageView.setFitWidth(64);
                imageView.setFitHeight(64);
                alert.setGraphic(imageView);
            }
        } catch (RuntimeException e) {
            appendLog("Failed to load About logo: " + e.getMessage());
        }

        alert.showAndWait();
    }

    private static String detectDockerPath() {
        String dockerPath = "Not found";

        try {
            String[] cmd;
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                cmd = new String[]{"cmd", "/c", "where", "docker.exe"};
            } else {
                cmd = new String[]{"which", "docker"};
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line != null && !line.isEmpty()) {
                    dockerPath = line;
                }
            }

            process.waitFor();

        } catch (IOException | InterruptedException e) {
            dockerPath = "Error detecting docker path: " + e.getMessage();
        }

        return dockerPath;
    }

    @FXML
    private void handleClearLogs() {
        logArea.clear();
    }

    @FXML
    private void handleStart() {
        CompositionVersion selected = compositionVersionTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("No composition/version selected.");
            return;
        }
        Composition composition = selected.getComposition();
        Version version = selected.getVersion();


        if (composition == null) {
            showError("No composition selected.");
            return;
        }
        if (version == null) {
            showError("No version selected.");
            return;
        }

        runCommandAsync(() -> {
            try {
                File composeFile = downloadComposeFile(composition.getName());
                if (composeFile == null) {
                    String msg = "Failed to download compose file for " + composition;
                    appendLog("ERROR: " + msg);
                    showError(msg);
                    return;
                }

                // Write .env file with IMAGE_TAG variable
                File envFile = new File(tempComposeDir, ".env");
                try (FileWriter fw = new FileWriter(envFile, StandardCharsets.UTF_8)) {
                    fw.write("IMAGE_TAG=" + version.getIdent() + "\n");
                }
                appendLog("Wrote environment file with IMAGE_TAG=" + version.getIdent());

                // Run docker compose with the env file
                String[] cmd = {
                        DOCKER_CMD, COMPOSE_CMD,
                        "--env-file", envFile.getAbsolutePath(),
                        "-f", composeFile.getAbsolutePath(),
                        UP_CMD, "-d"
                };
                _runCommand(cmd);

                appendLog("Started " + composition + " version " + version);
                activeComposeFile = composeFile;
            } catch (IOException e) {
                String msg = "Error starting composition: " + e.getMessage();
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

    @SuppressWarnings("OverlyComplexBooleanExpression")
    @FXML
    private void handleRefresh() {
        runCommandAsync(() -> {
            try {
                VersionsManifest manifest = fetchVersionManifest();
                if (manifest == null ||
                        manifest.compositions == null || manifest.compositions.isEmpty() ||
                        manifest.versions == null || manifest.versions.isEmpty()) {
                    appendLog("No compositions or versions available from server.");
                    showError("No compositions or versions available from server.");
                    return;
                }

                availableCompositions = manifest.compositions;
                availableVersions = manifest.versions;

                // Create all composition-version pairs
                List<CompositionVersion> combined = availableCompositions.stream()
                        .flatMap(comp -> availableVersions.stream()
                                .map(ver -> new CompositionVersion(comp, ver)))
                        .toList();

                Platform.runLater(() -> {
                    compositionVersionTable.getItems().setAll(combined);
                    compositionVersionTable.getSelectionModel().clearSelection();
                });

                appendLog("Refreshed compositions and versions.");
            } catch (IOException e) {
                appendLog("ERROR fetching manifest: " + e.getMessage());
                showError("Failed to fetch compositions/versions: " + e.getMessage());
            }

            refreshContainers();
        });
    }

    @FXML
    public final void initialize() {
        // Disable controls initially
        startButton.setDisable(true);
        stopAllButton.setDisable(true);

        memUsageColumn.setCellValueFactory(data -> data.getValue().memUsageProperty());

        compositionColumn.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().getCompositionName()));
        versionColumn.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().getVersionIdent()));
        commentColumn.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().getCompositionComment()));

        compositionVersionTable.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        // Enable Start/Stop when something is selected
        compositionVersionTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            boolean enabled = newV != null;
            startButton.setDisable(!enabled);
            stopAllButton.setDisable(!enabled);
        });

        containerTable.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        // Table bindings
        nameColumn.setCellValueFactory(data -> data.getValue().nameProperty());
        imageColumn.setCellValueFactory(data -> data.getValue().imageProperty());
        memUsageColumn.setCellValueFactory(data -> data.getValue().memUsageProperty());
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

    private VersionsManifest fetchVersionManifest(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();

        // Deserialize into simple POJO
        VersionsManifestData data = mapper.readValue(json, VersionsManifestData.class);

        // Convert to JavaFX-friendly classes
        List<Composition> compositions = data.compositions.stream()
                .map(d -> new Composition(d.name, d.comment))
                .toList();

        List<Version> versions = data.versions.stream()
                .map(d -> new Version(d.ident, d.comment))
                .toList();

        return new VersionsManifest(compositions, versions);
    }

    private VersionsManifest fetchVersionManifest() throws IOException {
        String json;

        if (ContainerKittyApplication.DEV_MODE) {
            appendLog("DEV mode enabled: using dev-versions.json from resources");
            try (InputStream in = getClass().getResourceAsStream("/dev-versions.json")) {
                if (in == null) throw new IOException("dev-versions.json not found in resources");
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } else {
            appendLog("Fetching versions.json via git...");
            json = fetchFileFromGit("docker/compose/versions.json");
        }

        return fetchVersionManifest(json);
    }

    private File downloadComposeFile(String composition) throws IOException {
        appendLog("Fetching compose file via git show...");
        String yaml = fetchFileFromGit("docker/compose/docker-compose-" + composition + ".yml");
        File outFile = new File(tempComposeDir, "docker-compose-" + composition + ".yml");
        Files.writeString(outFile.toPath(), yaml);
        appendLog("Fetched compose file: " + outFile.getName());
        return outFile;
    }

    private static final String REPO_URL = "git@gitlab.com:<namespace>/<repo>.git";
    private static final String GIT_BRANCH = "main";

    private String fetchFileFromGit(String pathInRepo) throws IOException {
        File tempDir = Files.createTempDirectory("ck_git_fetch").toFile();

        // Initialize empty git repo
        runAndLogCommand(new String[]{"git", "init"}, tempDir, "git init failed");
        runAndLogCommand(new String[]{"git", "remote", "add", "origin", REPO_URL}, tempDir, "git remote add failed");

        // Fetch just the branch head (no checkout)
        runAndLogCommand(new String[]{"git", "fetch", "--depth", "1", "origin", GIT_BRANCH}, tempDir, "git fetch failed");

        // Show the single file content
        ProcessBuilder pb = new ProcessBuilder("git", "show", GIT_BRANCH + ":" + pathInRepo);
        pb.directory(tempDir);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        @SuppressWarnings("StringBufferWithoutInitialCapacity") StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            //noinspection NestedAssignment,MethodCallInLoopCondition
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }

        try {
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("git show failed for " + pathInRepo + " (exit=" + exit + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git show interrupted for " + pathInRepo, e);
        } finally {
            deleteRecursive(tempDir);
        }

        return sb.toString();
    }

    private void runAndLogCommand(String[] command, File directory, String errorMessage) throws IOException {
        appendLog(String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        @SuppressWarnings("StringBufferWithoutInitialCapacity") StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            //noinspection NestedAssignment,MethodCallInLoopCondition
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                appendLog(errorMessage + ": " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(errorMessage + " (interrupted)", e);
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] files = file.listFiles();
        if (files != null) {
            for (File f : files) {
                deleteRecursive(f);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** Updates status label with running containers count and tooltip for non-running containers */
    private void updateStatus() {
        List<ContainerInfo> containers = containerTable.getItems();

        long running = containers.stream()
                .filter(c -> c.getStatus().startsWith("Up")).count();
        long total = containers.size();

        String statusText;
        String style;

        if (total == 0) {
            statusText = "Status: Stopped";
            style = "-fx-text-fill: red; -fx-font-weight: bold;";
        } else {
            statusText = "Status: " + running + "/" + total + " running";
            style = running == total
                    ? "-fx-text-fill: green; -fx-font-weight: bold;"
                    : "-fx-text-fill: orange; -fx-font-weight: bold;";
        }

        List<Label> containerLabels = containers.stream()
                .filter(c -> !c.getStatus().startsWith("Up"))
                .map(c -> {
                    Label lbl = new Label(c.getName() + " (" + c.getStatus() + ")");
                    lbl.setStyle("-fx-text-fill: red;");
                    return lbl;
                })
                .toList();

        Platform.runLater(() -> {
            statusLabel.setText(statusText);
            statusLabel.setStyle(style);

            if (containerLabels.isEmpty()) {
                statusLabel.setTooltip(null);
            } else {
                VBox tooltipBox = new VBox(2);
                tooltipBox.getChildren().addAll(containerLabels);

                ScrollPane scrollPane = new ScrollPane(tooltipBox);
                scrollPane.setPrefWidth(250);
                scrollPane.setPrefHeight(150);
                scrollPane.setFitToWidth(true);

                Tooltip tooltip = new Tooltip();
                tooltip.setGraphic(scrollPane);
                tooltip.setText(""); // no text, only graphic
                statusLabel.setTooltip(tooltip);
            }
        });
    }

    @SuppressWarnings("OverlyNestedMethod")
    private void refreshContainers() {
        // Remember currently selected container name
        ContainerInfo selected = containerTable.getSelectionModel().getSelectedItem();
        String selectedName = selected != null ? selected.getName() : null;

        Platform.runLater(() -> containerTable.getItems().clear());

        try {
            ProcessBuilder pb = new ProcessBuilder(DOCKER_CMD, "ps", "--format", "{{.Names}}|{{.Image}}|{{.Status}}");
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                //noinspection NestedAssignment,MethodCallInLoopCondition
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\|");
                    if (parts.length == 3) {
                        ContainerInfo container = new ContainerInfo(parts[0], parts[1], parts[2], null);
                        Platform.runLater(() -> {
                            containerTable.getItems().add(container);
                            if (selectedName != null && selectedName.equals(container.getName())) {
                                containerTable.getSelectionModel().select(container);
                            }
                        });
                    }
                }
            }
        } catch (IOException e) {
            appendLog("Error fetching containers: " + e.getMessage());
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private void runCommandAsync(Runnable task) {
        setControlsDisabled(true);
        executor.submit(() -> {
            try {
                task.run();
            } finally {
                setControlsDisabled(false);
            }
        });
    }

    /** Enables/disables controls on FX thread */
    private void setControlsDisabled(boolean disabled) {
        Platform.runLater(() -> containerTable.setDisable(disabled));
    }

    /** Internal command runner */
    private void _runCommand(String[] command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                //noinspection NestedAssignment,MethodCallInLoopCondition
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
    private static void showError(String message) {
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

    @Override
    public final String toString() {
        return "ContainerKittyController{}";
    }

    private boolean devMode;

    void setDevMode(boolean devMode) {
        this.devMode = devMode;
    }
}
