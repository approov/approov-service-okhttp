package io.approov.service.okhttp;

import java.io.UnsupportedEncodingException;
import java.util.List;

public class DefaultMessageSigningConfig implements MessageSigningConfig {
    // the name of the header that will be used to send the message signature
    private String targetHeader;
    // the list of headers that were added to the message
    private List<String> usedHeaders;
    // the message to be signed
    private String message;
    // the list of strings that can be used to help find issues on the server if a message signature doesn't match the required value
    private List<String> debugHelper;

    DefaultMessageSigningConfig(String targetHeader, List<String> usedHeaders, String message, List<String> debugHelper) {
        this.targetHeader = targetHeader;
        this.usedHeaders = usedHeaders;
        this.message = message;
        this.debugHelper = debugHelper;
    }

    public String getTargetHeaderName(){ return targetHeader; }
    public String getSigningMessage() { return message; }
    public String generateTargetHeaderValue(String messageSignature) throws UnsupportedEncodingException {
        // create a JSON object of the following form (although all blank space should be left out):
        // {
        //     "accountSig":"messageSignature.String()",
        //     "headers": [JSON usedHeaders list string values],
        //     "debugHelper": [JSON debug helpers list string values] // only add debugHelper if it is non-null
        // }
        // b64URL encode the JSON for communication in the target header
        String jsonData = "{...}";
        return android.util.Base64.encodeToString(jsonData.getBytes("UTF-8"), android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING).replace("\n", "");

    }
}
