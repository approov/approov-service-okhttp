package io.approov.service.okhttp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.approov.service.httpsig.*;
import okhttp3.HttpUrl;
import okhttp3.Request;

public class OkHttpMessageSignatureProvider implements ComponentProvider{

    private final Request request;
    private Map<String, Integer> counters;

    public OkHttpMessageSignatureProvider(Request request) {
        this.request = request;
    }

    @Override
    public String getMethod() {
        return request.method().toString();
    }

    @Override
    public String getAuthority() {
        return request.url().host();
    }

    @Override
    public String getScheme() {
        return request.url().scheme();
    }

    @Override
    public String getTargetUri() {
        return request.url().toString();
    }

    @Override
    public String getRequestTarget() {
        String requestTarget = "";

        if (request.url().encodedPath() != null) {
            requestTarget += request.url().encodedPath();  // Equivalent to getRawPath()
        }

        if (request.url().encodedQuery() != null) {
            requestTarget += "?" + request.url().encodedQuery();  // Equivalent to getRawQuery()
        }

        return requestTarget;

    }

    @Override
    public String getPath() {
        return request.url().encodedPath();
    }

    @Override
    public String getQuery() {
        return request.url().encodedQuery();
    }

    @Override
    public String getQueryParams(String s) {
        HttpUrl url = request.url(); // Get the URL from OkHttp request
        List<String> values = url.queryParameterValues(s); // Get all values for the given query parameter name

        if (values.size() == 1) {
            return values.get(0); // Single value, return directly
        } else if (values.isEmpty()) {
            throw new IllegalArgumentException("Could not find query parameter named " + s);
        } else {
            if (counters == null) {
                // Initialize counters if not set
                // TODO: this supports API 24+
                /*counters = url.queryParameterNames().stream()
                        .collect(Collectors.toMap(Function.identity(), k -> 0)); */
                // Initialize counters if not set (API 21 compatible way)
                counters = new HashMap<>();
                for (String key : url.queryParameterNames()) {
                    counters.put(key, 0);
                }
            }
            // TODO: this supports API 24+
            /*int count = counters.getOrDefault(s, 0); // Get the current count
            counters.put(s, count + 1); // Increment counter for next time
            int count = counters.getOrDefault(s, 0); // Get the current count */
            int count = counters.containsKey(s) ? counters.get(s) : 0;
            counters.put(s, count + 1); // Increment counter for next time

            return values.get(count); // Return the value at the previous count position
        }
    }

    @Override
    public String getStatus() {
        throw new UnsupportedOperationException("Requests cannot return a status code");
    }

    @Override
    public String getField(String s) {
        List<String> headers = request.headers(s);
        return ComponentProvider.combineFieldValues(headers);
    }
}
