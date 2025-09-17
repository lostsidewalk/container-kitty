package container.kitty;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class Version {
    private final StringProperty ident = new SimpleStringProperty();
    private final StringProperty comment = new SimpleStringProperty();

    public Version()
    {

    }

    public Version(String ident, String comment) {
        this.ident.set(ident);
        this.comment.set(comment);
    }

    public StringProperty identProperty() { return ident; }
    public StringProperty commentProperty() { return comment; }

    // Convenience getters
    public String getIdent() { return ident.get(); }
    public String getComment() { return comment.get(); }

    // Optional setters if needed
    public void setIdent(String ident) { this.ident.set(ident); }
    public void setComment(String comment) { this.comment.set(comment); }

    @Override
    public String toString() {
        return getIdent();
    }
}
