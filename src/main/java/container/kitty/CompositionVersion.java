package container.kitty;

public class CompositionVersion {
    private final Composition composition;
    private final Version version;

    public CompositionVersion(Composition composition, Version version) {
        this.composition = composition;
        this.version = version;
    }

    public Composition getComposition() {
        return composition;
    }

    public Version getVersion() {
        return version;
    }

    public String getCompositionName() {
        return composition != null ? composition.getName() : "";
    }

    public String getVersionIdent() {
        return version != null ? version.getIdent() : "";
    }

    public String getCompositionComment() {
        return composition != null ? composition.getComment() : "";
    }

    public String getVersionComment() {
        return version != null ? version.getComment() : "";
    }

    @Override
    public String toString() {
        return getCompositionName() + " / " + getVersionIdent();
    }
}
