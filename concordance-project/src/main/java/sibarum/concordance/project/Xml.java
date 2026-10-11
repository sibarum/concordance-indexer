package sibarum.concordance.project;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** XML, kept to the little that poms and {@code settings.xml} need. */
final class Xml {

    private Xml() {}

    /** The document element of {@code file}, or a {@link MavenProjectReader.BrokenReactorException} saying why not. */
    static Element parse(Path file) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // A pom is data from the tree being indexed, or from a repository, so it is not trusted: no external
            // entities, no DTDs.
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);   // poms use one namespace; ignoring it keeps tag names plain
            DocumentBuilder builder = factory.newDocumentBuilder();
            try (var in = Files.newInputStream(file)) {
                Document doc = builder.parse(in);
                return doc.getDocumentElement();
            }
        } catch (ParserConfigurationException | SAXException e) {
            throw new MavenProjectReader.BrokenReactorException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    static List<Element> childElements(Element parent) {
        NodeList children = parent.getChildNodes();
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element e) out.add(e);
        }
        return out;
    }

    /** The children of {@code parent} named {@code tag}, in document order. */
    static List<Element> childElements(Element parent, String tag) {
        return childElements(parent).stream().filter(e -> e.getTagName().equals(tag)).toList();
    }

    static Optional<Element> childElement(Element parent, String tag) {
        return childElements(parent).stream().filter(e -> e.getTagName().equals(tag)).findFirst();
    }

    /** The trimmed text of the child named {@code tag}, absent when there is no such child or it is empty. */
    static Optional<String> childText(Element parent, String tag) {
        return childElement(parent, tag).map(Xml::text).filter(s -> !s.isEmpty());
    }

    static String text(Element e) {
        return e.getTextContent() == null ? "" : e.getTextContent().trim();
    }
}
