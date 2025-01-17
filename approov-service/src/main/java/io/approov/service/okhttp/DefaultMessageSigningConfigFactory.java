package io.approov.service.okhttp;

import java.util.ArrayList;
import okhttp3.Request;

/* Add the following interfaces */
interface MessageSigningConfig {
    String getSigningMessage();
    String getTargetHeaderName();
    String generateTargetHeaderValue(String messageSignature);
}

interface MessageSigningConfigFactory {
    MessageSigningConfig generateMessageSigningConfig(Request request, String approovTokenHeader);
}

/* message signing configuration
* This class is used to configure the message signing feature. The message signature can be computed based on the
* request URL, the headers specified in signedHeaders in the order in which they are listed and, optionally, the
* body of the message. The signature will be added to the request headers using the header name specified in header.
* You can have multiple configurations for different domains and a default '*' configuration that will be used if no
* specific configuration is found for a domain.
*/
public final class DefaultMessageSigningConfigFactory implements MessageSigningConfigFactory {
    // the name of the header that will be used to send the message signature
    private String targetHeader;
    // the list of headers to include in the message to be signed, in the order they should be added
    private ArrayList<String> signedHeaders;
    // set to true to include debug helper strings in the generated message signing header
    private boolean includeDebugHelperStrings;

    // constructor
    public ApproovMessageSigningConfig(String targetHeader, ) {
        if (targetHeader == null || targetHeader.isEmpty())
            throw new IllegalArgumentException("The target header must be specified");
        this.targetHeader = targetHeader;
        this.signedHeaders = new ArrayList<>();
    }

    /* Get/set methods */

    /* Get target header */
    public String getTargetHeader() {
        return targetHeader;
    }

    /* Add a header to the list of signed headers: NOTE the sequence of headers DOES matter */
    public DefaultMessageSigningConfigFactory addSignedHeader(String header) {
        if (header == null || header.isEmpty())
            throw new IllegalArgumentException("The header must be specified");
        signedHeaders.add(header);
        return this;
    }

    public DefaultMessageSigningConfigFactory addDebugHelperStrings(boolean enablement) {
        includeDebugHelperStrings = enablement;
        return this;
    }

    // add html method to the message
    private addHTMLMethod(StringBuilder message, List<String> debugHelper, String method){
        message.append(method);
        message.append("\n");
        debugHelper.add(method.substring(0,2));
    }

    // Generate a SHA256 hash of the provided data, convert it to b64url and
    // return the specified number of characters at the start of the b64url string.
    private getB64URLDigestSnippet(byte[] data, int snippetLength) String {
        if (snippetLength <= 0 || data == null || data.length == 0) {
            return ""
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data.getBytes("UTF-8"));
        String b64UrlEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        if (b64UrlEncoded.length() <= snippetLength) {
            return b64UrlEncoded;
        }
        return b64UrlEncoded.substring(0, snippetLength);
    }

    // adds the URL string to the message - must include all bits of the url from the scheme through to the last param
    private addURL(StringBuilder message, List<String> debugHelper, String url) {
        int start = message.length()
        message.append(url);
        message.append("\n");
        if debugHelper != null {
            debugHelper.add(getB64URLDigestSnippet(message.substring(start).getBytes("UTF-8"), 6);
        }
    }
        
    // add a header to the message
    private addHeaderValues(List<String> usedHeaders, StringBuilder message, List<String> debugHelper, String headerName, List<String> headerValues) {
        if (values == null || values.isEmpty()) {
            return
        }
        usedHeaders.add(headerName);
        int start = message.length();
        String lowercaseName = headerName.toLowerCase();
        // add one headername:headervalue\n entry for each header value to be included in the signature
        for (String value : values) {
            message.append(lowercaseName).append(":");
            if (value != null) {
                message.append(value);
            }
            message.append("\n");
        }
        if debugHelper != null {
            debugHelper.add(getB64URLDigestSnippet(message.substring(start).getBytes("UTF-8"), 6);
        }
    }

    private addBody(StringBuilder message, List<String> debugHelper, Buffer body) {
        String b64UrlSha256 = body.sha256().base64Url();
        message.append(b64UrlSha256);
        if debugHelper != null {
            debugHelper.add(b64UrlSha256.substring(0, 6));
        }
    }

    MessageSigningConfig generateMessageSigningConfig(Request request, String approovTokenHeader){
        // capture the set of header names that are included in the message
        List<String> usedHeaders = new ArrayList<>();
        // build the message as a list of  
        StringBuilder message = new StringBuilder();
        // capture a list of properties that will help debug the construction of the message on the server side.
        // Every property added to the message has an associated entry in the debugHelper. That way, if the
        // server fails to match a signature it can iterate over the properties in the debug helper to determine
        // which property was incorrect while constructing the message. To keep the debug helper short it often
        // uses a substring of the base64 encoded SHA256 hash of the property added to the message.
        List<String> debugHelper = includeDebugHelperStrings ? new ArrayList<>() : null;

        // 1. Add the Method to the message
        addHTMLMethod(message, debugHelper, request.method());

        //  2. add the URL to the message, followed by a newline
        // TODO make sure this includes all the full URL - scheme through to all params
        addURL(message, debugHelper, request.url())

        // 3. add the Approov token header to the message
        List<String> values = request.headers(approovTokenHeader); // make sure the okhtp lookup works whatever the case used on the header name
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("provided request does not include the Approov token header");
        }
        addHeaderValues(usedHeaders, message, debugHelper, approovTokenHeader, values);

        // 4. add the required headers to the message as 'headername:headervalue', where the headername is in
        // lowercase
        if (messageSigningConfig.getSignedHeaders() != null) {
            for (String header : messageSigningConfig.signedHeaders) {
                addHeaderValues(message, debugHelper, usedHeaders, header, request.headers(header));
            }
        }

        // add the body to the message
        okhttp3.RequestBody body = request.body();
        if (body != null && !body.isOneShot()) { // we can't support one-shot bodies without making a copy - we probably need to do that extra work.
            Buffer buffer = new Buffer();
            body.writeTo(buffer);
            addBody(message, debugHelper, buffer);
        }
        return new DefaultMessageSigningConfig(targetHeader, usedHeaders, message.String(), debugHelper);
    }
}

public class DefaultMessageSigningConfig implements MessageSigningConfig {
    // the name of the header that will be used to send the message signature
    private String targetHeader;
    // the list of headers that were added to the message
    private List<String> usedHeaders;
    // the message to be signed
    private String message;
    // the list of strings that can be used to help find issues on the server if a message signature doesn't match the required value
    private List<String> debugHelper;

    DefaultMessageSigningConfig(String targetHeader, String usedHeaders, String message, List<String> debugHelper) {
        this.targeHeader = targetHeader;
        this.usedHeaders = usedHeaders;
        this.message = message;
        this.debugHelper = debugHelper;
    }

    public String getTargetHeaderName(){ return targetHeader; }
    public String getSigningMessage() { return usedHeadersSpec; }
    public String generateTargetHeaderValue(String messageSignature) {
        // create a JSON object of the following form (although all blank space should be left out):
        // {
        //     "accountSig":"messageSignature.String()",
        //     "headers": [JSON usedHeaders list string values],
        //     "debugHelper": [JSON debug helpers list string values] // only add debugHelper if it is non-null
        // }
        // b64URL encode the JSON for communication in the target header
        String jsonData = "{...}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(jsonData.getBytes("UTF-8"));
    }
}
