package container.kitty;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class ContainerKittyApplication extends Application {
    public static boolean DEV_MODE;

    @Override
    public void start(Stage primaryStage) throws Exception {
        // Parse command-line args
        Parameters params = getParameters();
        DEV_MODE = params.getRaw().contains("--dev");

        FXMLLoader loader = new FXMLLoader(ContainerKittyApplication.class.getResource("container-kitty-view.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root);

        primaryStage.setTitle("container-kitty");
        primaryStage.setScene(scene);

        // Set a more generous initial size
        primaryStage.setWidth(800);
        primaryStage.setHeight(600);

        // Optional: let it be resizable
        primaryStage.setMinWidth(600);
        primaryStage.setMinHeight(500);

        // Set proper close behavior
        primaryStage.setOnCloseRequest(event -> {
            ContainerKittyController controller = loader.getController();
            controller.shutdown();
            Platform.exit();
            System.exit(0);
        });

        primaryStage.show();
    }
}
