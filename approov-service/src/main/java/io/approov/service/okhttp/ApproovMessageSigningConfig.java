package io.approov.service.okhttp;

import java.util.ArrayList;

/* message signing configuration
* This class is used to configure the message signing feature. The message signature can be computed based on the
* request URL, the headers specified in signedHeaders in the order in which they are listed and, optionally, the
* body of the message. The signature will be added to the request headers using the header name specified in header.
* You can have multiple configurations for different domains and a default '*' configuration that will be used if no
* specific configuration is found for a domain.
*/
public class ApproovMessageSigningConfig {
    // the name of the header that will be used to send the message signature
    protected String targetHeader;
    // the list of headers to include in the message to be signed, in the order they should be added
    protected ArrayList<String> signedHeaders;
    // true if the message body should also be signed
    protected Boolean signBody;
    // true if the Approov token header is included in the signature
    protected Boolean signApproovToken;
    // true if target URL should be included in the signature
    protected Boolean signURL;
    // true if the network request method should be included in the signature
    protected Boolean signMethod;
    // constructor
    public ApproovMessageSigningConfig(String targetHeader) {
        if (targetHeader == null || targetHeader.isEmpty())
            throw new IllegalArgumentException("The target header must be specified");
        this.targetHeader = targetHeader;
        this.signedHeaders = new ArrayList<>();
        this.signBody = false;
        this.signApproovToken = true;
        this.signURL = true;
        this.signMethod = true;        
    }

    /* Get/set methods */

    /* Get target header */
    public String getTargetHeader() {
        return targetHeader;
    }
    /* Get signed headers */
    public ArrayList<String> getSignedHeaders() {
        return signedHeaders;
    }
    /* Add a header to the list of signed headers: NOTE the sequence of headers DOES matter */
    public void addSignedHeader(String header) {
        if (header == null || header.isEmpty())
            throw new IllegalArgumentException("The header must be specified");
        signedHeaders.add(header);
    }
    /* Get signBody flag */
    public Boolean getSignBody() {
        return signBody;
    }
    /* Set signBody flag */
    public void setSignBody(Boolean signBody) {
        this.signBody = signBody;
    }
    /* Get signApproovToken flag */
    public Boolean getSignApproovToken() {
        return signApproovToken;
    }
    /* Set signApproovToken flag */
    public void setSignApproovToken(Boolean signApproovToken) {
        this.signApproovToken = signApproovToken;
    }
    /* Get signURL flag */
    public Boolean getSignURL() {
        return signURL;
    }
    /* Set signURL flag */
    public void setSignURL(Boolean signURL) {
        this.signURL = signURL;
    }
    /* Get signMethod flag */
    public Boolean getSignMethod() {
        return signMethod;
    }
    /* Set signMethod flag */
    public void setSignMethod(Boolean signMethod) {
        this.signMethod = signMethod;
    }


}
