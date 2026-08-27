import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import java.nio.file.*;

/** Which real dependency artifacts can JarTypeSolver actually open? */
public class Jars {
    public static void main(String[] args) throws Exception {
        int ok = 0, bad = 0, missing = 0;
        for (String entry : Files.readString(Path.of(args[0])).trim().split(";")) {
            if (entry.isBlank() || !entry.endsWith(".jar")) continue;
            Path p = Path.of(entry);
            String name = p.getFileName().toString();
            if (!Files.exists(p)) {
                System.out.printf("  MISSING     %s%n", name);
                missing++;
                continue;
            }
            try {
                new JarTypeSolver(entry);
                ok++;
            } catch (Throwable t) {
                System.out.printf("  UNREADABLE  %-44s %s: %s%n", name, t.getClass().getSimpleName(),
                        String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
                bad++;
            }
        }
        System.out.printf("  -> %d readable, %d unreadable, %d missing%n", ok, bad, missing);
    }
}
