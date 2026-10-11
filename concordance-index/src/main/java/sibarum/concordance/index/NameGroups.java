package sibarum.concordance.index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link Search.Listener} that turns a stream of declarations into a stream of {@link NameGroup}s, for a view
 * that shows one row per name.
 *
 * <p>A group can grow after it is first shown: {@code draw} found in the project at stage two gains the library
 * declarations of {@code draw} at stage four. So each batch is reported as the groups it created or grew, each
 * whole as it now stands, and a view replaces the row it has for that name. Nothing a view has shown ever goes
 * away; rows only appear and gain declarations.
 *
 * <pre>{@code
 * NameGroups groups = new NameGroups(new NameGroups.Listener() {
 *     public void changed(Plan.Stage stage, List<NameGroup> changed) { ui.later(() -> rows.upsert(changed)); }
 * });
 * Search.start(corpus, plan, Query.names(typed), groups);
 * }</pre>
 */
public final class NameGroups implements Search.Listener<Symbol> {

    /** What a view of name groups hears. Every method has a default that ignores it. */
    public interface Listener {
        default void stage(Plan.Stage stage) {}

        /** Groups created or grown since the last call, each as it now stands, in the order first found. */
        default void changed(Plan.Stage stage, List<NameGroup> changed) {}

        default void paused(Plan.Stage stage) {}

        default void ended(Search.State state) {}
    }

    private final Listener downstream;
    private final Map<String, List<Hit<Symbol>>> byName = new LinkedHashMap<>();

    public NameGroups(Listener downstream) {
        this.downstream = downstream;
    }

    /** Every group so far, in the order first found. Safe to call from any thread. */
    public List<NameGroup> groups() {
        synchronized (byName) {
            List<NameGroup> out = new ArrayList<>();
            byName.keySet().forEach(name -> out.add(group(name)));
            return out;
        }
    }

    @Override
    public void stage(Plan.Stage stage) {
        downstream.stage(stage);
    }

    @Override
    public void hits(Plan.Stage stage, List<Hit<Symbol>> hits) {
        List<NameGroup> changed = new ArrayList<>();
        synchronized (byName) {
            Set<String> touched = new LinkedHashSet<>();
            for (Hit<Symbol> hit : hits) {
                byName.computeIfAbsent(hit.item().name(), k -> new ArrayList<>()).add(hit);
                touched.add(hit.item().name());
            }
            touched.forEach(name -> changed.add(group(name)));
        }
        downstream.changed(stage, changed);
    }

    @Override
    public void paused(Plan.Stage stage) {
        downstream.paused(stage);
    }

    @Override
    public void ended(Search.State state) {
        downstream.ended(state);
    }

    private NameGroup group(String name) {
        List<Hit<Symbol>> declared = byName.get(name);
        return new NameGroup(name, declared.getFirst().rank(), declared);
    }
}
