/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.binding.xml.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.binding.BindingInfo;
import org.openhab.core.config.core.ConfigDescription;
import org.openhab.core.config.xml.util.ConverterAttributeMapValidator;
import org.openhab.core.config.xml.util.GenericUnmarshaller;
import org.openhab.core.config.xml.util.NodeIterator;

import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;

/**
 * The {@link BindingInfoConverter} is a concrete implementation of the {@code XStream} {@link Converter} interface used
 * to convert binding information within an XML document
 * into a {@link BindingInfoXmlResult} object.
 * <p>
 * This converter converts {@code binding} XML tags.
 *
 * @author Michael Grammling - Initial contribution
 * @author Andre Fuechsel - Made author tag optional
 */
@NonNullByDefault
public class BindingInfoConverter extends GenericUnmarshaller<BindingInfoXmlResult> {

    private ConverterAttributeMapValidator attributeMapValidator;

    public BindingInfoConverter() {
        super(BindingInfoXmlResult.class);

        attributeMapValidator = new ConverterAttributeMapValidator(
                new String[][] { { "id", "true" }, { "schemaLocation", "false" } });
    }

    private @Nullable URI readConfigDescriptionURI(NodeIterator nodeIterator) throws ConversionException {
        String uriText = nodeIterator.nextAttribute("config-description-ref", "uri", false);

        if (uriText != null) {
            try {
                return new URI(uriText);
            } catch (URISyntaxException ex) {
                throw new ConversionException(
                        "The URI '" + uriText + "' in node " + "'config-description-ref' is invalid!", ex);
            }
        }

        return null;
    }

    private @Nullable ConfigDescription readConfigDescription(NodeIterator nodeIterator) {
        Object nextNode = nodeIterator.next();

        if (nextNode != null) {
            if (nextNode instanceof ConfigDescription) {
                return (ConfigDescription) nextNode;
            }

            nodeIterator.revert();
        }

        return null;
    }

    @Override
    public @Nullable Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
        // read attributes
        Map<String, String> attributes = attributeMapValidator.readValidatedAttributes(reader);

        String id = requireNonEmpty(attributes.get("id"), "Binding id attribute is null or empty");

        // set automatically extracted URI for a possible 'config-description' section
        context.put("config-description.uri", "binding:" + id);

        // read values
        List<?> nodes = (List<?>) context.convertAnother(context, List.class);
        NodeIterator nodeIterator = new NodeIterator(nodes);

        String name = requireNonEmpty((String) nodeIterator.nextValue("name", true),
                "Binding name attribute is null or empty");
        String description = (String) nodeIterator.nextValue("description", false);
        String author = (String) nodeIterator.nextValue("author", false);
        String serviceId = (String) nodeIterator.nextValue("service-id", false);

        URI configDescriptionURI = readConfigDescriptionURI(nodeIterator);
        ConfigDescription configDescription = null;
        if (configDescriptionURI == null) {
            configDescription = readConfigDescription(nodeIterator);
            if (configDescription != null) {
                configDescriptionURI = configDescription.getUID();
            }
        }

        nodeIterator.assertEndOfType();

        // create object
        BindingInfo bindingInfo = new BindingInfo(id, name, description, author, serviceId, configDescriptionURI);
        return new BindingInfoXmlResult(bindingInfo, configDescription);
    }
}
