package container.kitty;

import java.util.List;

// Only used for JSON deserialization
public class VersionsManifestData {
    public List<CompositionData> compositions;
    public List<VersionData> versions;

    public static class CompositionData {
        public String name;
        public String comment;
    }

    public static class VersionData {
        public String ident;
        public String comment;
    }
}
