/**
 * Copyright 2011-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.informant.plugin.servlet;

import io.informant.api.Message;
import io.informant.api.MessageSupplier;
import io.informant.shaded.google.common.base.Joiner;
import io.informant.shaded.google.common.base.Objects;
import io.informant.shaded.google.common.base.Optional;
import io.informant.shaded.google.common.collect.ImmutableMap;
import io.informant.shaded.google.common.collect.Maps;

import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Servlet span captured by AspectJ pointcut.
 * 
 * Similar thread safety issues as {@link JdbcMessageSupplier}, see documentation in that class for
 * more info.
 * 
 * This span gets to piggyback on the happens-before relationships created by putting other spans
 * into the concurrent queue which ensures that session state is visible at least up to the start of
 * the most recent span.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class ServletMessageSupplier extends MessageSupplier {

    // TODO allow additional notation for session attributes to capture, e.g.
    // +currentControllerContext.key which would denote to capture the value of that attribute at
    // the beginning of the request

    // it would be convenient to just store the request object here
    // but it appears that tomcat (at least, maybe others) clears out those
    // objects after the response is complete so that it can reuse the
    // request object for future requests
    // since the data is persisted in a separate thread to avoid slowing up the user's
    // request, the request object could have been cleared before the request data
    // is persisted, so instead the request parts that are needed must be cached
    //
    // another problem with storing the request object here is that it may not be thread safe
    //
    // the http session object also cannot be stored here because it may be marked
    // expired when the session attributes are persisted, so instead
    // (references to) the session attributes must be stored here

    @Nullable
    private final String requestMethod;
    @Nullable
    private final String requestURI;
    @Nullable
    private volatile ImmutableMap<String, String[]> requestParameterMap;

    // the initial value is the sessionId as it was present at the beginning of the request
    @Nullable
    private final String sessionIdInitialValue;
    @Nullable
    private volatile String sessionIdUpdatedValue;

    // session attributes may not be thread safe, so they must be converted to Strings
    // within the request processing thread, which can then be used by the stuck trace alerting
    // threads and real-time monitoring threads
    // the initial value map contains the session attributes as they were present at the beginning
    // of the request
    @Nullable
    private final ImmutableMap<String, String> sessionAttributeInitialValueMap;

    // ConcurrentHashMap does not allow null values, so need to use Optional values
    @Nullable
    private volatile ConcurrentMap<String, Optional<String>> sessionAttributeUpdatedValueMap;

    ServletMessageSupplier(@Nullable String requestMethod, @Nullable String requestURI,
            @Nullable String sessionId,
            @Nullable ImmutableMap<String, String> sessionAttributeMap) {

        this.requestMethod = requestMethod;
        this.requestURI = requestURI;
        this.sessionIdInitialValue = sessionId;
        if (sessionAttributeMap == null || sessionAttributeMap.isEmpty()) {
            this.sessionAttributeInitialValueMap = null;
        } else {
            this.sessionAttributeInitialValueMap = sessionAttributeMap;
        }
    }

    public Message get() {
        ImmutableMap.Builder<String, Object> detail = ImmutableMap.builder();
        addRequestParameterDetail(detail);
        addSessionAttributeDetail(detail);
        String message;
        if (requestMethod == null && requestURI == null) {
            message = "";
        } else if (requestMethod == null) {
            message = Objects.firstNonNull(requestURI, "");
        } else if (requestURI == null) {
            message = Objects.firstNonNull(requestMethod, "");
        } else {
            message = requestMethod + " " + requestURI;
        }
        return Message.withDetail(message, detail.build());
    }

    boolean isRequestParameterMapCaptured() {
        return requestParameterMap != null;
    }

    void captureRequestParameterMap(Map<?, ?> requestParameterMap) {
        // shallow copy is necessary because request may not be thread safe
        // shallow copy is also necessary because of the note about tomcat above
        ImmutableMap.Builder<String, String[]> map = ImmutableMap.builder();
        for (Entry<?, ?> entry : requestParameterMap.entrySet()) {
            String key = (String) entry.getKey();
            String[] value = (String[]) entry.getValue();
            if (value == null) {
                // just to be safe since ImmutableMap won't accept nulls
                map.put(key, new String[0]);
            } else {
                // the clone() is just to be safe to ensure immutability
                map.put(key, value.clone());
            }
        }
        this.requestParameterMap = map.build();
    }

    void setSessionIdUpdatedValue(String sessionId) {
        this.sessionIdUpdatedValue = sessionId;
    }

    void putSessionAttributeChangedValue(String name, @Nullable String value) {
        if (sessionAttributeUpdatedValueMap == null) {
            sessionAttributeUpdatedValueMap = Maps.newConcurrentMap();
        }
        sessionAttributeUpdatedValueMap.put(name, Optional.fromNullable(value));
    }

    @Nullable
    String getSessionIdInitialValue() {
        return sessionIdInitialValue;
    }

    @Nullable
    String getSessionIdUpdatedValue() {
        return sessionIdUpdatedValue;
    }

    private void addRequestParameterDetail(ImmutableMap.Builder<String, Object> detail) {
        if (requestParameterMap != null && !requestParameterMap.isEmpty()) {
            ImmutableMap.Builder<String, Object> nestedDetail = ImmutableMap.builder();
            for (String parameterName : requestParameterMap.keySet()) {
                String[] values = requestParameterMap.get(parameterName);
                if (values.length == 0) {
                    nestedDetail.put(parameterName, "");
                } else if (values.length == 1) {
                    nestedDetail.put(parameterName, values[0]);
                } else {
                    nestedDetail.put(parameterName, Joiner.on(", ").join(values));
                }
            }
            detail.put("request parameters", nestedDetail.build());
        }
    }

    private void addSessionAttributeDetail(ImmutableMap.Builder<String, Object> detail) {
        if (sessionIdUpdatedValue != null) {
            detail.put("session id (at beginning of this request)",
                    Objects.firstNonNull(sessionIdInitialValue, ""));
            detail.put("session id (updated during this request)", sessionIdUpdatedValue);
        } else if (sessionIdInitialValue != null) {
            detail.put("session id", sessionIdInitialValue);
        }
        if (sessionAttributeInitialValueMap != null && sessionAttributeUpdatedValueMap == null) {
            // session attributes were captured at the beginning of the request, and no session
            // attributes were updated mid-request
            detail.put("session attributes", sessionAttributeInitialValueMap);
        } else if (sessionAttributeInitialValueMap == null
                && sessionAttributeUpdatedValueMap != null) {
            // no session attributes were available at the beginning of the request, and session
            // attributes were updated mid-request
            detail.put("session attributes (updated during this request)",
                    sessionAttributeUpdatedValueMap);
        } else if (sessionAttributeUpdatedValueMap != null) {
            // session attributes were updated mid-request
            ImmutableMap.Builder<String, Object> sessionAttributeInitialValuePlusMap =
                    ImmutableMap.builder();
            sessionAttributeInitialValuePlusMap.putAll(sessionAttributeInitialValueMap);
            // add empty values into initial values for any updated attributes that are not already
            // present in initial values nested detail map
            for (Entry<String, Optional<String>> entry : sessionAttributeUpdatedValueMap
                    .entrySet()) {
                if (!sessionAttributeInitialValueMap.containsKey(entry.getKey())) {
                    sessionAttributeInitialValuePlusMap.put(entry.getKey(), Optional.absent());
                }
            }
            detail.put("session attributes (at beginning of this request)",
                    sessionAttributeInitialValuePlusMap.build());
            detail.put("session attributes (updated during this request)",
                    sessionAttributeUpdatedValueMap);
        } else {
            // both initial and updated value maps are null so there is nothing to add to the
            // detail map
        }
    }
}