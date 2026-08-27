import java.util.List;
class UsesJdk {
    void run() {
        String s = "hello";
        s.length();
        List.of(1, 2, 3).size();
        List.of("a", "b").stream().map(String::trim).toList();
    }
}
