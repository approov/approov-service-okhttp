package io.approov.service.okhttp;

import java.util.ArrayList;
import okhttp3.Request;

/* Add the following interfaces */
interface MessageSigningConfig {
    String getSigningMessage();
    String getTargetHeaderName();
    String generateTargetHeaderValue(String messageSignature, String approovTokenHeader);
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
public class DefaultMessageSigningConfigFactory implements MessageSigningConfigFactory {
    // the name of the header that will be used to send the message signature
    protected String targetHeader;
    // the list of headers to include in the message to be signed, in the order they should be added
    protected ArrayList<String> signedHeaders;

    // constructor
    public ApproovMessageSigningConfig(String targetHeader) {
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

    MessageSigningConfig generateMessageSigningConfig(Request request, String approovTokenHeader){
        StringBuilder usedHeadersSpec = new StringBuilder();
        StringBuilder message = new StringBuilder();
        // 1. Add the Method to the message
        message.append(request.method());
        message.append("\n");
        //  2. add the URL to the message, followed by a newline
        message.append(request.url()); // TODO make sure this includes all the URL params if there are any
        message.append("\n");
        // 3. add the Approov token header to the message
        List<String> values = request.headers(approovTokenHeader); // make sure the okhtp lookup works whatever the case used on the header name
        usedHeadersSpec.append(approovTokenHeader.toLowerCase() + "," + values.size())
        for (String value : values) {
            message.append(approovTokenHeader.toLowerCase()).append(":");
            if (value != null) {
                message.append(value);
            }
            message.append("\n");
        }

        // 4. add the required headers to the message as 'headername:headervalue', where the headername is in
        // lowercase
        if (messageSigningConfig.getSignedHeaders() != null) {
            for (String header : messageSigningConfig.signedHeaders) {
                // add one headername:headervalue\n entry for each header value to be included in the signature
                List<String> values = request.headers(header);
                if (values != null && values.length() > 0) {
                    usedHeadersSpec.append(approovTokenHeader.toLowerCase()+ "," + values.size())
                    for (String value : values) {
                        message.append(header.toLowerCase()).append(":");
                        if (value != null) {
                            message.append(value);
                        }
                        message.append("\n");
                    }
                }
            }
        }
        String finalUsedHeadersSpec = usedHeadersSpec.String()
        message.append(finalUsedHeadersSpec)

        // add the body to the message
        okhttp3.RequestBody body = request.body();
        if (body != null) {
            Buffer buffer = new Buffer();
            body.writeTo(buffer);
            message.append(buffer.readUtf8());
        }
        return new DefaultMessageSigningConfig(
        );
    }
}

public class DefaultMessageSigningConfig implements MessageSigningConfig
    // the name of the header that will be used to send the message signature
    private String targetHeader;
    // the list of headers with counts that are expected by the server and were also included in the message to be signed
    private String usedHeadersSpec;
    // the message to be signed
    private String message;

    DefaultMessageSigningConfig(String targetHeader, String usedHeadersSpec, String message) {
        this.targeHeader = targetHeader;
        this.usedHeaderSpec = usedHeaderSpec;
        this.message = message;
    }

    public String getTargetHeaderName(){ return targetHeader }
    public String getSigningMessage() { return usedHeadersSpec }
    public String generateTargetHeaderValue(String messageSignature) {
        return usedHeaderSpec + ";" + messageSignature;
    }
}
