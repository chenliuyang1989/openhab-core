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
package org.openhab.core.automation.internal.module.handler;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.automation.ModuleHandlerCallback;
import org.openhab.core.automation.Trigger;
import org.openhab.core.automation.handler.BaseTriggerModuleHandler;
import org.openhab.core.automation.handler.TriggerHandlerCallback;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventFilter;
import org.openhab.core.events.EventSubscriber;
import org.openhab.core.items.events.ItemCommandEvent;
import org.openhab.core.types.Command;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is an ModuleHandler implementation for Triggers which trigger the rule
 * if an item receives a command. The eventType and command value can be set with the
 * configuration.
 *
 * @author Kai Kreuzer - Initial contribution
 */
@NonNullByDefault
public class ItemCommandTriggerHandler extends BaseTriggerModuleHandler implements EventSubscriber, EventFilter {

    public static final String MODULE_TYPE_ID = "core.ItemCommandTrigger";

    public static final String CFG_ITEMNAME = "itemName";
    public static final String CFG_COMMAND = "command";

    private final Logger logger = LoggerFactory.getLogger(ItemCommandTriggerHandler.class);

    private final String itemName;
    private final @Nullable String command;
    private final String topic;

    private final Set<String> types;
    private final BundleContext bundleContext;

    private @Nullable ServiceRegistration<?> eventSubscriberRegistration;

    public ItemCommandTriggerHandler(Trigger module, BundleContext bundleContext) {
        super(module);
        this.itemName = (String) module.getConfiguration().get(CFG_ITEMNAME);
        this.command = (String) module.getConfiguration().get(CFG_COMMAND);
        this.types = Set.of(ItemCommandEvent.TYPE);
        this.bundleContext = bundleContext;
        Dictionary<String, Object> properties = new Hashtable<>();
        this.topic = "openhab/items/" + itemName + "/command";
        properties.put("event.topics", topic);
        eventSubscriberRegistration = this.bundleContext.registerService(EventSubscriber.class.getName(), this,
                properties);
    }

    @Override
    public Set<String> getSubscribedEventTypes() {
        return types;
    }

    @Override
    public @Nullable EventFilter getEventFilter() {
        return this;
    }

    @Override
    public void receive(Event event) {
        ModuleHandlerCallback callback = this.callback;
        if (callback != null) {
            logger.trace("Received Event: Source: {} Topic: {} Type: {}  Payload: {}", event.getSource(),
                    event.getTopic(), event.getType(), event.getPayload());
            Map<String, Object> values = new HashMap<>();
            if (event instanceof ItemCommandEvent) {
                String command = this.command;
                Command itemCommand = ((ItemCommandEvent) event).getItemCommand();
                if (command == null || command.equals(itemCommand.toFullString())) {
                    values.put("command", itemCommand);
                    values.put("event", event);
                    ((TriggerHandlerCallback) callback).triggered(this.module, values);
                }
            }
        }
    }

    /**
     * do the cleanup: unregistering eventSubscriber...
     */
    @Override
    public void dispose() {
        super.dispose();
        if (eventSubscriberRegistration != null) {
            eventSubscriberRegistration.unregister();
            eventSubscriberRegistration = null;
        }
    }

    @Override
    public boolean apply(Event event) {
        logger.trace("->FILTER: {}:{}", event.getTopic(), itemName);
        return event.getTopic().equals(topic);
    }
}
