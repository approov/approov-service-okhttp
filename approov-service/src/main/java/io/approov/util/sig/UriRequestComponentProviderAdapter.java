package io.approov.util.sig;

import android.net.Uri;

import java.util.List;

/**
 * @author jricher
 *
 */
public abstract class UriRequestComponentProviderAdapter extends RequestComponentProviderAdapter {


    private final Uri uri;


    public UriRequestComponentProviderAdapter(Uri uri)
    {
        this.uri = uri;
    }


    @Override
    public String getAuthority()
    {
        return uri.getAuthority();
    }


    @Override
    public String getScheme()
    {
        return uri.getScheme();
    }


    @Override
    public String getTargetUri()
    {
        return uri.toString();
    }


    @Override
    public String getRequestTarget()
    {
        StringBuilder target = new StringBuilder();
        String rawPath = uri.getEncodedPath(); //getEncodedPath() instead of getRawPath()
        if (rawPath != null) {
            target.append(rawPath);
        }
        String rawQuery = uri.getEncodedQuery(); //getEncodedQuery() instead of getRawQuery()
        if (rawQuery != null) {
            target.append("?").append(rawQuery);
        }
        return target.toString();
    }


    @Override
    public String getPath()
    {
        return uri.getPath();
    }


    @Override
    public String getQuery()
    {
        return uri.getQuery();
    }


    @Override
    public String getQueryParam(String name)
    {
        List<String> aParams =  uri.getQueryParameters(name);
        if (aParams == null || aParams.isEmpty()) {
            return null;
        }
        if (aParams.size() > 1) {
            throw new IllegalArgumentException("Found two named parameters, unsupported operation");
        }
        return aParams.get(0);
    }
}
