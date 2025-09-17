package container.kitty;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class Composition {
    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty comment = new SimpleStringProperty();

    public Composition() { }

    public Composition(String name, String comment) {
        this.name.set(name);
        this.comment.set(comment);
    }

    public StringProperty nameProperty() { return name; }
    public StringProperty commentProperty() { return comment; }

    // Convenience getters
    public String getName() { return name.get(); }
    public String getComment() { return comment.get(); }

    // Optional setters if needed
    public void setName(String name) { this.name.set(name); }
    public void setComment(String comment) { this.comment.set(comment); }

    @Override
    public String toString() {
        return getName();
    }
}
