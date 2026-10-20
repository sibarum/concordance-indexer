package probe.served;

/** Exists only in the jar the probe's WorkspaceReader serves; nowhere in ~/.m2 or any remote repository. */
public class Hello {
    public static String greet() { return "served by the probe WorkspaceReader"; }
}
