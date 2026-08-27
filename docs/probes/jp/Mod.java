import com.github.javaparser.*;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
public class Mod {
  public static void main(String[] a) {
    var r = new JavaParser(new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_25))
        .parse("import module java.base;\nimport java.base;\nimport static java.util.List.of;\nimport java.util.*;\nclass A {}\n");
    r.getResult().orElseThrow().getImports().forEach(i ->
        System.out.printf("  %-32s isModule=%-5s isStatic=%-5s isAsterisk=%s%n",
            i.toString().trim(), i.isModule(), i.isStatic(), i.isAsterisk()));
  }
}
