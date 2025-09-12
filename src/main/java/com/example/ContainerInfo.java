package com.example;

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
}
