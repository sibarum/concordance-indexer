import java.util.TreeMap;
import java.time.LocalDate;
import java.util.regex.Pattern;
class UsesUnseenJdk {
    void run() {
        TreeMap<String, Integer> m = new TreeMap<>();
        m.firstKey();
        LocalDate d = LocalDate.of(2026, 8, 26);
        d.plusDays(3);
        Pattern.compile("x").matcher("xyz");
    }
}
