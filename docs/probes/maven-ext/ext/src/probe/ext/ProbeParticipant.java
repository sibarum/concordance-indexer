package probe.ext;

import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;

/** Proves the jar loaded as a core extension: prints where it came from and which reader the session got. */
@Named("probe")
@Singleton
public class ProbeParticipant extends AbstractMavenLifecycleParticipant {

    @Override
    public void afterSessionStart(MavenSession session) {
        System.out.println("[probe-ext] afterSessionStart, loaded from "
                + ProbeParticipant.class.getProtectionDomain().getCodeSource().getLocation()
                + ", cwd=" + System.getProperty("user.dir")
                + ", maven.ext.class.path=" + System.getProperty("maven.ext.class.path"));
    }

    @Override
    public void afterProjectsRead(MavenSession session) {
        System.out.println("[probe-ext] afterProjectsRead, workspaceReader="
                + session.getRepositorySession().getWorkspaceReader());
    }
}
