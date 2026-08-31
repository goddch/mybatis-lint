package com.mybatislint.analyzer.xml;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * Builds {@link SAXParser} instances that are hardened against XXE and other
 * external-entity based attacks, and that never attempt to download the
 * MyBatis mapper DTD (or any other external DTD/schema) over the network.
 */
public final class SafeXmlParserFactory {

    private SafeXmlParserFactory() {
    }

    public static SAXParser newSaxParser() {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            factory.setXIncludeAware(false);

            // Mapper files commonly declare a DOCTYPE referencing the MyBatis DTD,
            // so DOCTYPE declarations themselves are still allowed. What is
            // disabled is any resolution of *external* entities/DTD subsets, so
            // parsing never attempts a network fetch and is not vulnerable to XXE.
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

            SAXParser parser = factory.newSAXParser();
            parser.getXMLReader().setEntityResolver((publicId, systemId) ->
                    new org.xml.sax.InputSource(new java.io.StringReader("")));
            return parser;
        } catch (ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IllegalStateException("Failed to configure a safe XML parser", e);
        }
    }
}
