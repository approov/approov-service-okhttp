package io.approov.util.sig;

import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.Request;

public class TestComponentProvider implements ComponentProvider {
    Request request;
    TestComponentProvider(Request request) {
        this.request = request;
    }
    @Override
    public String getMethod() {
        return request.method();
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
        return request.url().uri().toString();
    }

    @Override
    public String getRequestTarget() {
        String reqt = "";
        if (request.url().uri().getRawPath() != null) {
            reqt += request.url().uri().getRawPath();
        }
        if (request.url().uri().getRawQuery() != null) {
            reqt += "?" + request.url().uri().getRawQuery();
        }
        return reqt;
    }

    @Override
    public String getPath() {
        return request.url().uri().getRawPath();
    }

    @Override
    public String getQuery() {
        return request.url().uri().getRawQuery();
    }

    @Override
    public String getQueryParam(String name) {
        List<String> values = request.url().queryParameterValues(name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Could not find query parameter named " + name);
        } else if (values.size() > 1) {
            // From 2.2.8 of the spec: If a parameter name occurs multiple times in a request, the
            // named query parameter MUST NOT be included. If multiple parameters are common within
            // an application, it is RECOMMENDED to sign the entire query string using the @query
            // component identifier defined in Section 2.2.7.

            // to indicate that a query param must not be included, we return null
            return null;
        }
        return values.get(0);
    }

    @Override
    public String getStatus() {
        throw new IllegalStateException("Only requests are supported");
    }

    @Override
    public boolean hasField(String name) {
        List<String> headers = request.headers(name);
        return !headers.isEmpty();
    }

    @Override
    public String getField(String name) {
        List<String> headers = request.headers(name);
        return ComponentProvider.combineFieldValues(headers);
    }

    @Override
    public boolean hasBody() {
        return request.body() != null;
    }
}
