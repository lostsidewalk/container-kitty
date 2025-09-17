package container.kitty;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class ContainerInfo {
    private final StringProperty name;
    private final StringProperty image;
    private final StringProperty status;
    private final StringProperty memUsage;

    public ContainerInfo(String name, String image, String status, String memUsage) {
        this.name = new SimpleStringProperty(name);
        this.image = new SimpleStringProperty(image);
        this.status = new SimpleStringProperty(status);
        this.memUsage = new SimpleStringProperty(memUsage);
    }

    public StringProperty nameProperty() { return name; }
    public StringProperty imageProperty() { return image; }
    public StringProperty statusProperty() { return status; }
    public StringProperty memUsageProperty() { return memUsage; }

    // Convenience getters
    public String getName() { return name.get(); }
    public String getImage() { return image.get(); }
    public String getStatus() { return status.get(); }
    public String getMemUsage() { return memUsage.get(); }

    // Optional setters if needed
    public void setName(String name) { this.name.set(name); }
    public void setImage(String image) { this.image.set(image); }
    public void setStatus(String status) { this.status.set(status); }
    public void setMemUsage(String memUsage) { this.memUsage.set(memUsage); }

    @Override
    public String toString() {
        return "ContainerInfo{name=" + getName() +
                ", image=" + getImage() +
                ", status=" + getStatus() +
                ", memUsage=" + getMemUsage() + "}";
    }
}
