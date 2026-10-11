package sibarum.concordance.project;

/** A Maven artifact's name: {@code groupId:artifactId:version}. */
public record Coordinates(String groupId, String artifactId, String version) {

    public Coordinates {
        if (groupId == null || groupId.isEmpty() || artifactId == null || artifactId.isEmpty()
                || version == null || version.isEmpty()) {
            throw new IllegalArgumentException("Coordinates need a groupId, artifactId and version: "
                    + groupId + ":" + artifactId + ":" + version);
        }
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
