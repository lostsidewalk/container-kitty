package container.kitty;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class ContainerInfo {
    private final StringProperty name;
    private final StringProperty image;
    private final StringProperty status;

    public ContainerInfo(String name, String image, String status) {
        this.name = new SimpleStringProperty(name);
        this.image = new SimpleStringProperty(image);
        this.status = new SimpleStringProperty(status);
    }

    public StringProperty nameProperty() { return name; }
    public StringProperty imageProperty() { return image; }
    public StringProperty statusProperty() { return status; }

    // Convenience getters
    public String getName() { return name.get(); }
    public String getImage() { return image.get(); }
    public String getStatus() { return status.get(); }

    // Optional setters if needed
    public void setName(String name) { this.name.set(name); }
    public void setImage(String image) { this.image.set(image); }
    public void setStatus(String status) { this.status.set(status); }

    @Override
    public String toString() {
        return "ContainerInfo{name=" + getName() +
                ", image=" + getImage() +
                ", status=" + getStatus() + "}";
    }
}
