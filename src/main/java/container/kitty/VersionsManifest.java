package container.kitty;

import java.util.List;

public class VersionsManifest {
    public final List<Composition> compositions;
    public final List<Version> versions;

    public VersionsManifest(List<Composition> compositions, List<Version> versions) {
        this.compositions = compositions;
        this.versions = versions;
    }

    @Override
    public String toString() {
        return "VersionsManifest{" +
                "compositions=" + compositions +
                ", versions=" + versions +
                '}';
    }
}
