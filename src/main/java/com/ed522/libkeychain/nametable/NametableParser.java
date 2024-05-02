package com.ed522.libkeychain.nametable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.MalformedParametersException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.ed522.libkeychain.message.Message;
import com.ed522.libkeychain.nametable.transactions.TransactionReference;

public class NametableParser {

    private NametableParser() {}
    
    public static Nametable parse(File file) throws SAXException, IOException, ParserConfigurationException, DOMException, ReflectiveOperationException {

        Nametable nametable = new Nametable("", "");

        DocumentBuilderFactory fac = DocumentBuilderFactory.newInstance();
        fac.setIgnoringComments(true);
        DocumentBuilder builder = fac.newDocumentBuilder();

        Document root = builder.parse(file);

        // Get group name
        nametable.setGroup(
            root.getElementsByTagName("grpname").item(0).getTextContent()
        );
        // Get extension name
        nametable.setExtension(
            root.getElementsByTagName("extname").item(0).getTextContent()
        );

        // Parse messages
        NodeList msgs = root.getElementsByTagName("msg");

        for (int i = 0; i < msgs.getLength(); i++) {

            Document msgDoc = msgs.item(i).getOwnerDocument();
            MessageEntry msg = new MessageEntry(msgDoc.getElementsByTagName("name").item(0).getTextContent(), nametable.getGroup(), nametable.getExtension());

            NodeList fields = msgDoc.getElementsByTagName("field");

            for (int j = 0; j < fields.getLength(); j++) {
                
                msg.addField(new FieldEntry(
                    FieldEntry.parseType(
                        fields.item(j)
                               .getOwnerDocument()
                               .getElementsByTagName("type")
                               .item(0)
                               .getTextContent()
                    ),
                    fields.item(j)
                          .getOwnerDocument()
                          .getElementsByTagName("name")
                          .item(0)
                          .getTextContent()
                    )
                );

            }

            NodeList proofs = msgDoc.getElementsByTagName("proof");
            if (proofs.getLength() == 0) continue;
            if (proofs.item(0).getAttributes().getNamedItem("factor") != null) {
                msg.setProofFactor(Integer.valueOf(
                    proofs.item(0)
                          .getAttributes()
                          .getNamedItem("factor")
                          .getTextContent()
                ));
            } else {
                String[] raw = null;
                try {
                    raw = proofs.item(0)
                                .getAttributes()
                                .getNamedItem("formula")
                                .getTextContent()
                                .split("#");
                } catch (Exception e) {
                    throw new MalformedParametersException("Malformed type, use form com.package.Class#method");
                }
                Method method = Class.forName(raw[0]).getMethod(raw[1], Message.class);
                if (!Modifier.isStatic(method.getModifiers()) || !method.canAccess(null)) throw new NoSuchMethodException("Proof formula is not static or inaccessible");

            }

            msg.setSigned(Boolean.valueOf(
                proofs.item(0)
                      .getAttributes()
                      .getNamedItem("signed")
                      .getTextContent()
            ));

        }

        NodeList transactions = root.getElementsByTagName("transaction");

        for (int i = 0; i < transactions.getLength(); i++) {

            Document doc = transactions.item(i).getOwnerDocument();
            TransactionReference reference;
            Class<?> type;
            Method method;

            NodeList jrefs = doc.getElementsByTagName("jref");
            if (jrefs.getLength() == 0) continue;

            Document jref = jrefs.item(0).getOwnerDocument();

            type = Class.forName(
                jref.getElementsByTagName("jclass")
                    .item(0)
                    .getTextContent()
            );
            method = type.getMethod(
                jref.getElementsByTagName("jmethod")
                    .item(0)
                    .getTextContent()
            );

            reference = new TransactionReference(type, method, Boolean.parseBoolean(
                jref.getAttributes()
                    .getNamedItem("initialize")
                    .getTextContent()
            ));

            nametable.getTransactions().add(reference);

        }

        return nametable; 

    }

}
