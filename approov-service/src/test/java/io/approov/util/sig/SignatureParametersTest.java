package io.approov.util.sig;

import static org.junit.Assert.*;

import org.junit.Test;

public class SignatureParametersTest {

    @Test
    public void toComponentValue() {
        assertParams("minimal",
                new SignatureParameters()
                        .setCreated(123L)
                        .setKeyid("my-key")
                        .setAlg("my-alg"),
                "();created=123;keyid=\"my-key\";alg=\"my-alg\""
        );
        assertParams("selective",
                new SignatureParameters()
                        .addComponentIdentifier(ComponentProvider.DC_AUTHORITY)
                        .addComponentIdentifier("Content-Type")
                        .setCreated(123L)
                        .setKeyid("my-key"),
                "(\"@authority\" \"content-type\");created=123;keyid=\"my-key\""
        );
        assertParams("full",
                new SignatureParameters()
                        .addComponentIdentifier("Date")
                        .addComponentIdentifier(ComponentProvider.DC_METHOD)
                        .addComponentIdentifier(ComponentProvider.DC_PATH)
                        .addComponentIdentifier(ComponentProvider.DC_QUERY)
                        .addComponentIdentifier(ComponentProvider.DC_AUTHORITY)
                        .addComponentIdentifier("Content-Type")
                        .addComponentIdentifier("Content-Digest")
                        .addComponentIdentifier("Content-Length")
                        .setCreated(123L)
                        .setKeyid("my-key"),
                "(\"date\" \"@method\" \"@path\" \"@query\" \"@authority\" \"content-type\" \"content-digest\" \"content-length\");created=123;keyid=\"my-key\""
        );
    }

    private void assertParams(String name, SignatureParameters params, String expected) {
        String actual = params.toComponentValue().serialize();
        assertEquals("Params failure - "+name, expected, actual);
    }
}