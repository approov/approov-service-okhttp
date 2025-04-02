package io.approov.service.okhttp;

import java.util.List;

/**
 * ApproovRequestMutations stores information about changes made to a network request
 * during Approov processing, such as token headers, substituted headers, and query parameters.
 */
public class ApproovRequestMutations {
    private String tokenHeaderKey;
    private List<String> substitutionHeaderKeys;
    private String originalURL;
    private List<String> substitutionQueryParamKeys;

    /**
     * Gets the header key used for the Approov token.
     *
     * @return the Approov token header key
     */
    public String getTokenHeaderKey() {
        return tokenHeaderKey;
    }

    /**
     * Sets the header key used for the Approov token.
     *
     * @param tokenHeaderKey the Approov token header key
     */
    public void setTokenHeaderKey(String tokenHeaderKey) {
        this.tokenHeaderKey = tokenHeaderKey;
    }

    /**
     * Gets the list of headers that were substituted with secure strings.
     *
     * @return the list of substituted header keys
     */
    public List<String> getSubstitutionHeaderKeys() {
        return substitutionHeaderKeys;
    }

    /**
     * Sets the list of headers that were substituted with secure strings.
     *
     * @param substitutionHeaderKeys the list of substituted header keys
     */
    public void setSubstitutionHeaderKeys(List<String> substitutionHeaderKeys) {
        this.substitutionHeaderKeys = substitutionHeaderKeys;
    }

    /**
     * Gets the original URL before any query parameter substitutions.
     *
     * @return the original URL
     */
    public String getOriginalURL() {
        return originalURL;
    }

    /**
     * Gets the list of query parameter keys that were substituted with secure strings.
     *
     * @return the list of substituted query parameter keys
     */
    public List<String> getSubstitutionQueryParamKeys() {
        return substitutionQueryParamKeys;
    }

    /**
     * Sets the results of query parameter substitutions, including the original URL and the keys of substituted parameters.
     *
     * @param originalURL the original URL before substitutions
     * @param substitutionQueryParamKeys the list of substituted query parameter keys
     */
    public void setSubstitutionQueryParamResults(String originalURL, List<String> substitutionQueryParamKeys) {
        this.originalURL = originalURL;
        this.substitutionQueryParamKeys = substitutionQueryParamKeys;
    }
}
