package probe.ext;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Named;
import javax.inject.Singleton;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;

/**
 * Serves artifacts from {@code served/} beside this jar, laid out as
 * {@code served/<groupId>/<artifactId>-<version>[-<classifier>].<extension>}, and logs every call.
 */
@Named("ide")
@Singleton
public class ProbeWorkspaceReader implements WorkspaceReader {

    private final WorkspaceRepository repository = new WorkspaceRepository("probe-ide");
    private final File served;

    public ProbeWorkspaceReader() throws Exception {
        File jar = new File(ProbeWorkspaceReader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        served = new File(jar.getParentFile(), "served");
        System.out.println("[probe-reader] created, serving " + served);
    }

    @Override
    public WorkspaceRepository getRepository() {
        return repository;
    }

    @Override
    public File findArtifact(Artifact a) {
        String name = a.getArtifactId() + "-" + a.getVersion()
                + (a.getClassifier().isEmpty() ? "" : "-" + a.getClassifier()) + "." + a.getExtension();
        File f = new File(new File(served, a.getGroupId()), name);
        File hit = f.isFile() ? f : null;
        System.out.println("[probe-reader] findArtifact " + a + " -> " + (hit == null ? "null" : "SERVED " + hit));
        return hit;
    }

    @Override
    public List<String> findVersions(Artifact a) {
        List<String> versions = new ArrayList<>();
        String prefix = a.getArtifactId() + "-";
        String suffix = "." + a.getExtension();
        String[] names = new File(served, a.getGroupId()).list();
        if (names != null) {
            for (String n : names) {
                if (n.startsWith(prefix) && n.endsWith(suffix)) {
                    String v = n.substring(prefix.length(), n.length() - suffix.length());
                    if (!v.isEmpty() && Character.isDigit(v.charAt(0)) && !v.contains("-")) versions.add(v);
                }
            }
        }
        System.out.println("[probe-reader] findVersions " + a + " -> " + versions);
        return versions;
    }
}
