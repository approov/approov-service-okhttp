package io.approov.service.okhttp;

import java.util.List;

public class ApproovRequestMutations {
    private String tokenHeaderKey;
    private List<String> substitutionHeaderKeys;
    private String originalURL;
    private List<String> substitutionQueryParamKeys;

    public String getTokenHeaderKey() {
        return tokenHeaderKey;
    }

    public void setTokenHeaderKey(String tokenHeaderKey)
    {
        this.tokenHeaderKey = tokenHeaderKey;
    }

    public List<String> getSubstitutionHeaderKeys() {
        return substitutionHeaderKeys;
    }

    public void setSubstitutionHeaderKeys(List<String> substitutionHeaderKeys) {
        this.substitutionHeaderKeys = substitutionHeaderKeys;
    }

    public String getOriginalURL() {
        return originalURL;
    }

    public List<String> getSubstitutionQueryParamKeys() {
        return substitutionQueryParamKeys;
    }

    public void setSubstitutionQueryParamResults(String originalURL, List<String> substitutionQueryParamKeys) {
        this.originalURL = originalURL;
        this.substitutionQueryParamKeys = substitutionQueryParamKeys;
    }


}
