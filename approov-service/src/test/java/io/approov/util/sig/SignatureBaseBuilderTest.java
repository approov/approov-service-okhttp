package io.approov.util.sig;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

import okhttp3.Headers;
import okhttp3.Request;

public class SignatureBaseBuilderTest {
    private static final Logger LOGGER = Logger.getLogger( SignatureBaseBuilderTest.class.getName() );

    @Test
    public void createSignatureBase() {
        Headers headers1 = new Headers.Builder()
                .add("My-Header", "my \tValuE")
                .add("My-Other-Header", "my other\tValuE")
                .build();
        Request request1 = new Request.Builder()
                .get()
                .url("https://example.com:1234/path/seg%201/seg+2/?param1=&param2=arg%201&param3=Arg+3#fragment")
                .headers(headers1)
                .build();

        LOGGER.info(makeLines(
                "Request properties",
                "        toString:" + request1.url(),
                "          scheme:" + request1.url().scheme(),
                "            host:" + request1.url().host(),
                "            port:" + request1.url().port(),
                "    encoded path:" + request1.url().encodedPath(),
                "           query:" + request1.url().query(),
                "   encoded query:" + request1.url().encodedQuery(),
                "parameter param1:" + request1.url().queryParameter("param1"),
                "parameter param2:" + request1.url().queryParameter("param2"),
                "parameter param3:" + request1.url().queryParameter("param3"),
                "        fragment:" + request1.url().fragment(),
                "encoded fragment:" + request1.url().encodedFragment()
        ));

        assertSignatureBase("minimal",
                new SignatureParameters()
                        .setCreated(123L)
                        .setKeyid("my-key")
                        .setAlg("my-alg"),
                request1,
                makeLines("\"@signature-params\": ();created=123;keyid=\"my-key\";alg=\"my-alg\"")
        );
        assertSignatureBase("path and authority",
                new SignatureParameters()
                        .setCreated(123L)
                        .setKeyid("my-key")
                        .addComponentIdentifier(ComponentProvider.DC_PATH)
                        .addComponentIdentifier(ComponentProvider.DC_AUTHORITY),
                request1,
                makeLines(
                        "\"@path\": /path/seg%201/seg+2/",
                        "\"@authority\": example.com",
                        "\"@signature-params\": (\"@path\" \"@authority\");created=123;keyid=\"my-key\"")
        );
        assertSignatureBase("target-uri",
                new SignatureParameters()
                        .setCreated(123L)
                        .setKeyid("my-key")
                        .addComponentIdentifier(ComponentProvider.DC_TARGET_URI),
                request1,
                makeLines(
                        "\"@target-uri\": https://example.com:1234/path/seg%201/seg+2/?param1=&param2=arg%201&param3=Arg+3#fragment",
                        "\"@signature-params\": (\"@target-uri\");created=123;keyid=\"my-key\"")
        );
    }

    private void assertSignatureBase(String name,
                                SignatureParameters params,
                                Request request,
                                String expected
    ) {
        ComponentProvider provider = new TestComponentProvider(request);
        SignatureBaseBuilder baseBuilder = new SignatureBaseBuilder(params, provider);
        String actual = baseBuilder.createSignatureBase();

        LOGGER.log(Level.INFO, "Signature base for - {0}:\n{1}\n", new Object[]{name, actual});

        assertEquals("Signature base failure - "+name, expected, actual);
    }
    private String makeLines(String ... lines) {
        StringBuilder builder = new StringBuilder();
        boolean isFirst = true;
        for (String line : lines) {
            if (isFirst) {
                builder.append(line);
                isFirst = false;
            } else {
                builder.append("\n").append(line);
            }
        }
        return builder.toString();
    }
}