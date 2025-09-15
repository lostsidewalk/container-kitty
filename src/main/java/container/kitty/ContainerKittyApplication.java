package container.kitty;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class ContainerKittyApplication extends Application {
    @Override
    public void start(Stage primaryStage) throws Exception {
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

        primaryStage.show();
    }
}
