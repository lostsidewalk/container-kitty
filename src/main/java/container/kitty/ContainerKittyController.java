package container.kitty;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

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

    private static final Pattern PATTERN = Pattern.compile("[^a-z0-9-_]");
    private static final String[] EMPTY_CMD = new String[0];
    private static final CompletableFuture<?>[] EMPTY_FUTURES = new CompletableFuture[0];

    @FXML private Label statusLabel;
    @FXML private TextArea logArea;
    @FXML private TableView<ContainerInfo> containerTable;
    @FXML private TableColumn<ContainerInfo, String> nameColumn;
    @FXML private TableColumn<ContainerInfo, String> imageColumn;
    @FXML private TableColumn<ContainerInfo, String> statusColumn;
    @FXML private TableColumn<ContainerInfo, String> projectColumn;
    @FXML private Button startButton;
    @FXML private Button stopAllButton;
    @FXML private Button stopButton;
    @FXML private TableView<CompositionVersion> compositionVersionTable;
    @FXML private TableColumn<CompositionVersion, String> compositionColumn;
    @FXML private TableColumn<CompositionVersion, String> versionColumn;
    @FXML private TableColumn<CompositionVersion, String> commentColumn;

    // local cache of lists
    private List<Composition> availableCompositions = List.of();
    private List<Version> availableVersions = List.of();
    private Timeline statusUpdater;
    private File tempComposeDir;

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

        ScrollPane scrollPane = new ScrollPane(textArea);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setPrefSize(500, 300);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About Container Kitty");
        alert.setHeaderText("Container Kitty Launcher");
        alert.getDialogPane().setContent(scrollPane);
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

            process.waitFor(60, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            dockerPath = "Error detecting docker path: " + e.getMessage();
        }

        return dockerPath;
    }

    @FXML
    private void handleClearLogs() {
        logArea.clear();
    }

    private String activeComposeProject; // project name of the running composition

    @FXML
    private void handleStart() {
        if (activeComposeProject != null) {
            showError("Another composition is already running: " + activeComposeProject);
            return;
        }

        CompositionVersion selected = compositionVersionTable.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getComposition() == null || selected.getVersion() == null) {
            showError("No composition/version selected.");
            return;
        }

        String projectName = sanitizeProjectName(selected.getCompositionName() + "-" + selected.getVersionIdent());
        activeComposeProject = projectName;
        updateButtons();

        runCommandAsync(() -> {
            try {
                File composeFile = downloadComposeFile(selected.getCompositionName());
                if (composeFile == null) {
                    String msg = "Failed to download compose file for " + selected.getCompositionName();
                    appendLog("ERROR: " + msg);
                    showError(msg);
                    activeComposeProject = null;
                    Platform.runLater(this::updateButtons);
                    return;
                }

                // Write .env file
                File envFile = new File(tempComposeDir, ".env");
                Files.writeString(envFile.toPath(), "IMAGE_TAG=" + selected.getVersionIdent() + "\n", StandardCharsets.UTF_8);
                appendLog("Wrote environment file with IMAGE_TAG=" + selected.getVersionIdent());

                // Run docker-compose up
                String[] cmd = dockerCmd(
                        COMPOSE_CMD,
                        "-p", projectName,
                        "--env-file", envFile.getAbsolutePath(),
                        "-f", composeFile.getAbsolutePath(),
                        UP_CMD, "-d"
                );

                _runCommand(cmd, null, "Failed to start composition " + projectName);

                appendLog("Started " + selected.getCompositionName() + " version " + selected.getVersionIdent());
            } catch (IOException e) {
                String msg = "Error starting composition: " + e.getMessage();
                appendLog("ERROR: " + msg);
                showError(msg);
                activeComposeProject = null;
                Platform.runLater(this::updateButtons);
            }
        });
    }

    private static String sanitizeProjectName(String name) {
        // Lowercase, replace non-alphanumeric chars with dash
        return PATTERN.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("-");
    }

    private void detectActiveComposeProject() {
        try {
            ProcessBuilder pb = new ProcessBuilder(DOCKER_CMD, "ps", "--format",
                    "{{.Names}}|{{.Label \"com.docker.compose.project\"}}|{{.Label \"com.docker.compose.service\"}}|{{.Label \"com.docker.compose.version\"}}");
            String command = String.join(" ", pb.command());
            appendLog("Command: " + command);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                //noinspection MethodCallInLoopCondition,NestedAssignment
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\|");
                    if (parts.length >= 2) {
                        String project = parts[1];
                        if (project != null && !project.isEmpty()) {
                            activeComposeProject = project;
                            appendLog("Detected active composition project: " + activeComposeProject);
                            Platform.runLater(this::updateButtons);
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            appendLog("Failed to detect active composition: " + e.getMessage());
        }
    }

    @FXML
    private void handleStop() {
        if (activeComposeProject == null) {
            appendLog("No active composition to stop.");
            return;
        }

        String projectToStop = activeComposeProject;
        // Reset immediately so UI updates
        activeComposeProject = null;
        updateButtons();

        String[] cmd = dockerCmd(COMPOSE_CMD, "-p", projectToStop, DOWN_CMD);
        _runCommand(cmd, null, "Failed to stop composition " + projectToStop);

        appendLog("Stopped active composition: " + projectToStop);
        refreshContainers();
    }

    @FXML
    private void handleStopAll() {
        // Reset immediately
        activeComposeProject = null;
        updateButtons();

        List<String> runningProjects = containerTable.getItems().stream()
                .filter(c -> c.getStatus().startsWith("Up"))
                .map(ContainerInfo::getProject)
                .distinct()
                .toList();

        if (runningProjects.isEmpty()) return;

        List<CompletableFuture<Void>> stopFutures = runningProjects.stream()
                .map(project -> CompletableFuture.runAsync(() -> _runCommand(dockerCmd(COMPOSE_CMD, "-p", project, DOWN_CMD), null, "Failed to stop " + project), executor))
                .toList();

        CompletableFuture.allOf(stopFutures.toArray(EMPTY_FUTURES))
                .thenRun(this::refreshContainers);

        refreshContainers();
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

    private static String[] dockerCmd(String... args) {
        List<String> cmd = new ArrayList<>(16);
        cmd.add(DOCKER_CMD);
        cmd.addAll(Arrays.asList(args));

        return cmd.toArray(EMPTY_CMD);
    }

    @FXML
    public final void initialize() {
        // Disable controls initially
        startButton.setDisable(true);
        stopAllButton.setDisable(true);
        stopButton.setDisable(true); // default

        // cell value factories
        compositionColumn.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().getCompositionName()));
        versionColumn.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().getVersionIdent()));
        commentColumn.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().getCompositionComment()));
        projectColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getProject()));

        // pref width
        compositionColumn.setPrefWidth(200);
        versionColumn.setPrefWidth(120);
        commentColumn.prefWidthProperty().bind(
                compositionVersionTable.widthProperty()
                        .subtract(compositionColumn.widthProperty())
                        .subtract(versionColumn.widthProperty())
                        .subtract(35) // small adjustment for scrollbar/margins
        );
        projectColumn.setPrefWidth(150); // adjust as needed

        // selection model
        compositionVersionTable.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        compositionVersionTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> updateButtons());
        containerTable.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

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
        projectColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getProject()));

        try {
            tempComposeDir = Files.createTempDirectory("docker-compose-temp").toFile();
            tempComposeDir.deleteOnExit();
        } catch (IOException e) {
            String msg = "Cannot create temporary folder for compose files: " + e.getMessage();
            appendLog("ERROR: " + msg);
            showError(msg);
        }

        CompletableFuture.runAsync(() -> {
            detectActiveComposeProject(); // detect before populating
            refreshContainers();
            Platform.runLater(this::updateButtons);
        }, executor).thenRunAsync(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (InterruptedException ignored) {}
            handleRefresh();
        }, executor);

        statusUpdater = new Timeline(new KeyFrame(Duration.seconds(5), event -> {
            refreshContainers();
            updateStatus();
        }));
        statusUpdater.setCycleCount(Timeline.INDEFINITE);
        statusUpdater.play();
    }

    @SuppressWarnings("OverlyBroadThrowsClause")
    private static VersionsManifest fetchVersionManifest(String json) throws IOException {
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

    /** Downloads (or loads from classpath in dev mode) the compose file for the given composition. */
    private File downloadComposeFile(String composition) throws IOException {
        // clean old .yml files optionally
        File[] oldFiles = tempComposeDir.listFiles((d, name) -> name.endsWith(".yml"));
        if (oldFiles != null) {
            for (File f : oldFiles) f.delete();
        }

        String fileName = String.format("docker-compose-%s.yml", composition);
        File outFile = new File(tempComposeDir, fileName);

        if (ContainerKittyApplication.DEV_MODE) {
            // In dev mode: load compose from classpath resource
            String resourcePath = "/docker/compose/" + fileName;
            appendLog("DEV mode: loading compose from classpath: " + resourcePath);

            try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    appendLog("Compose resource not found in classpath: " + resourcePath);
                    throw new IOException("Compose resource not found in classpath: " + resourcePath);
                }
                // Copy resource stream into temporary file for docker-compose to read
                Files.copy(in, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            appendLog("Loaded compose file from resources: " + outFile.getAbsolutePath());
            return outFile;
        } else {
            // Production: fetch the compose file from git
            appendLog("Fetching compose file via git show...");
            String yaml = fetchFileFromGit("docker/compose/" + fileName);
            Files.writeString(outFile.toPath(), yaml, StandardCharsets.UTF_8);
            appendLog("Fetched compose file: " + outFile.getName());
            return outFile;
        }
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

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] files = file.listFiles();
        if (files != null) {
            for (File f : files) {
                deleteRecursive(f);
            }
        }
        if (!file.delete()) {
            appendLog("Failed to delete file or directory: " + file.getAbsolutePath());
        }
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
            if (activeComposeProject != null) {
                statusText += " | Active Composition: " + activeComposeProject;
            }
            style = running == total
                    ? "-fx-text-fill: green; -fx-font-weight: bold;"
                    : "-fx-text-fill: orange; -fx-font-weight: bold;";
        }

        String s = statusText;
        Platform.runLater(() -> {
            statusLabel.setText(s);
            statusLabel.setStyle(style);
        });
    }

    private void refreshContainers() {
        runCommandAsync(() -> {
            try {
                Collection<ContainerInfo> containers = new ArrayList<>(256);
                ContainerInfo selected = containerTable.getSelectionModel().getSelectedItem();
                String selectedName = selected != null ? selected.getName() : null;

                ProcessBuilder pb = new ProcessBuilder(
                        DOCKER_CMD, "ps", "--format",
                        "{{.Names}}|{{.Image}}|{{.Status}}|{{.Label \"com.docker.compose.project\"}}|{{.RunningFor}}"
                );
                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    //noinspection NestedAssignment,MethodCallInLoopCondition
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split("\\|");
                        if (parts.length >= 4) {
                            containers.add(new ContainerInfo(parts[0], parts[1], parts[2], parts[3], null));
                        }
                    }
                }

                process.waitFor();

                Platform.runLater(() -> {
                    containerTable.getItems().setAll(containers);
                    if (selectedName != null) {
                        containers.stream()
                                .filter(c -> selectedName.equals(c.getName()))
                                .findFirst()
                                .ifPresent(container -> containerTable.getSelectionModel().select(container));
                    }
                    updateButtons();
                });
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
                String errorMessage = "Error fetching containers: " + e.getMessage();
                appendLog(errorMessage);
            }
        });
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "container-kitty-cmd");
        t.setDaemon(true);
        return t;
    });

    // This queue guarantees sequential execution, never overlap
    private CompletableFuture<Void> commandQueue = CompletableFuture.completedFuture(null);

    @SuppressWarnings("MethodMayBeSynchronized")
    private void runCommandAsync(Runnable task) {
        if (executor.isShutdown()) {
            appendLog("Executor is shutting down; skipping command.");
            return;
        }

        synchronized (this) {
            commandQueue = commandQueue.thenRunAsync(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    appendLog("Command failed: " + t.getMessage());
                    Platform.runLater(() -> showError("Command failed: " + t.getMessage()));
                } finally {
                    Platform.runLater(this::updateButtons);
                }
            }, executor);
        }
    }

    /** Executes a command synchronously inside the sequential queue */
    private void _runCommand(String[] command, File workingDir, String errorMessage) {
        runCommandAsync(() -> {
            appendLog("Command: " + String.join(" ", command));
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) pb.directory(workingDir);
            pb.redirectErrorStream(true);
            pb.environment().put("BUILDKIT_PROGRESS", "plain");

            try {
                Process process = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    //noinspection MethodCallInLoopCondition,NestedAssignment
                    while ((line = reader.readLine()) != null) {
                        appendLog(line);
                    }
                }

                int exitCode = process.waitFor();
                appendLog("Command exited with code: " + exitCode);
                if (exitCode != 0 && errorMessage != null) {
                    showError(errorMessage);
                }
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
                appendLog("Error executing command: " + e.getMessage());
                if (errorMessage != null) {
                    showError(errorMessage + "\n" + e.getMessage());
                }
            }
        });
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

    final void shutdown() {
        if (statusUpdater != null) {
            statusUpdater.stop();
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void updateButtons() {
        CompositionVersion selected = compositionVersionTable.getSelectionModel().getSelectedItem();

        boolean anyRunning = containerTable.getItems().stream()
                .anyMatch(c -> c.getStatus().startsWith("Up"));

        boolean selectedRunning = selected != null && containerTable.getItems().stream()
                .anyMatch(c -> c.getProject().equals(
                        sanitizeProjectName(selected.getCompositionName() + "-" + selected.getVersionIdent()))
                        && c.getStatus().startsWith("Up"));

        startButton.setDisable(activeComposeProject != null || selected == null);
        stopButton.setDisable(!selectedRunning);
        stopAllButton.setDisable(!anyRunning);
    }
}
