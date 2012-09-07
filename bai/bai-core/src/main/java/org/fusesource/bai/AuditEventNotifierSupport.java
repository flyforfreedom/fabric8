/*
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.bai;

import java.util.EventObject;

import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Producer;
import org.apache.camel.management.PublishEventNotifier;
import org.apache.camel.management.event.AbstractExchangeEvent;
import org.apache.camel.management.event.ExchangeCreatedEvent;
import org.apache.camel.management.event.ExchangeFailedEvent;
import org.apache.camel.management.event.ExchangeFailureHandledEvent;
import org.apache.camel.management.event.ExchangeSendingEvent;
import org.apache.camel.management.event.ExchangeSentEvent;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.ServiceHelper;
import org.apache.camel.util.URISupport;

/**
 */
public abstract class AuditEventNotifierSupport extends PublishEventNotifier {
    protected CamelContext camelContext;
    protected Endpoint endpoint;
    protected String endpointUri;
    private Producer producer;

    /**
     * Add a unique dispatchId property to the original Exchange, which will come back to us later.
     * Camel does not correlate the individual sends/dispatches of the same exchange to the same endpoint, e.g.
     * Exchange X sent to http://localhost:8080, again sent to http://localhost:8080... When both happen in parallel, and are marked in_progress in BAI, when the Sent or Completed
     * events arrive, BAI won't know which record to update (ambiguity)
     * So to overcome this situation, we enrich the Exchange with a DispatchID only when Created or Sending
     */
    @Override
    public void notify(EventObject event) throws Exception {
        AuditEvent auditEvent = null;
        AbstractExchangeEvent ae = null;
        if (event instanceof AuditEvent) {
            auditEvent = (AuditEvent) event;
            ae = auditEvent.getEvent();
        } else if (event instanceof AbstractExchangeEvent) {
            ae = (AbstractExchangeEvent) event;
            auditEvent = createAuditEvent(ae);
        }

        if (ae == null || auditEvent == null) {
            log.debug("Ignoring events like " + event + " as its neither a AbstractExchangeEvent or AuditEvent");
            return;
        }
        if (event instanceof ExchangeSendingEvent || event instanceof ExchangeCreatedEvent) {
            ae.getExchange().setProperty(AuditConstants.DISPATCH_ID, ae.getExchange().getContext().getUuidGenerator().generateUuid());
        }

        // only notify when we are started
        if (!isStarted()) {
            log.debug("Cannot publish event as notifier is not started: {}", event);
            return;
        }

        // only notify when camel context is running
        if (!camelContext.getStatus().isStarted()) {
            log.debug("Cannot publish event as CamelContext is not started: {}", event);
            return;
        }

        Exchange exchange = producer.createExchange();
        exchange.getIn().setBody(createPayload(auditEvent));

        // make sure we don't send out events for this as well
        // mark exchange as being published to event, to prevent creating new events
        // for this as well (causing a endless flood of events)
        exchange.setProperty(Exchange.NOTIFY_EVENT, Boolean.TRUE);
        try {
            producer.process(exchange);
        } finally {
            // and remove it when its done
            exchange.removeProperty(Exchange.NOTIFY_EVENT);
        }
    }

    /**
     * Creates the audit payload from the audit event
     */
    protected Object createPayload(AuditEvent auditEvent) {
        return auditEvent;
    }

    /**
     * Factory method to create a new {@link org.fusesource.bai.AuditEvent} in case a sub class wants to create a different derived kind of event
     */
    protected AuditEvent createAuditEvent(AbstractExchangeEvent ae) {
        return new AuditEvent(ae.getExchange(), ae);
    }

    /**
     * Substitute all arrays with CopyOnWriteArrayLists
     */
    @Override
    protected void doStart() throws Exception {
        ObjectHelper.notNull(camelContext, "camelContext", this);
        if (endpoint == null && endpointUri == null) {
            throw new IllegalArgumentException("Either endpoint or endpointUri must be configured");
        }

        if (endpoint == null) {
            endpoint = camelContext.getEndpoint(endpointUri);
        }

        producer = endpoint.createProducer();
        ServiceHelper.startService(producer);

    }

    @Override
    protected void doStop() throws Exception {
        ServiceHelper.stopService(producer);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + (endpoint != null ? endpoint : URISupport
                .sanitizeUri(endpointUri)) + "]";
    }

    @Override
    public boolean isEnabled(EventObject event) {
        EventObject coreEvent = event;
        AbstractExchangeEvent exchangeEvent = null;
        if (event instanceof AuditEvent) {
            AuditEvent auditEvent = (AuditEvent) event;
            coreEvent = auditEvent.getEvent();
        }
        if (event instanceof AbstractExchangeEvent) {
            exchangeEvent = (AbstractExchangeEvent) event;
        }
        return isEnabledFor(coreEvent, exchangeEvent);
    }

    protected abstract boolean isEnabledFor(EventObject coreEvent, AbstractExchangeEvent exchangeEvent);

    public CamelContext getCamelContext() {
        return camelContext;
    }

    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public String getEndpointUri() {
        return endpointUri;
    }

    public void setEndpointUri(String endpointUri) {
        this.endpointUri = endpointUri;
    }
}
