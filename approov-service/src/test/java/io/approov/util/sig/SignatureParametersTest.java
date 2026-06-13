package io.approov.util.sig;

import static org.junit.Assert.*;

import org.junit.Test;
import java.util.Collections;
import io.approov.util.http.sfv.BooleanItem;
import io.approov.util.http.sfv.Parameters;
import io.approov.util.http.sfv.StringItem;

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

    @Test
    public void containsComponentIdentifier() {
        SignatureParameters params = new SignatureParameters();

        // Add a component identifier without parameters
        params.addComponentIdentifier("content-type");

        // Add a component identifier with parameters
        Parameters sfvParams = Parameters.valueOf(
            Collections.singletonMap("req", (Object) BooleanItem.valueOf(true))
        );
        StringItem itemWithParams = StringItem.valueOf("accept").withParams(sfvParams);
        params.addComponentIdentifier(itemWithParams);

        // Test containsComponentIdentifier(String) - ignores parameters
        assertTrue(params.containsComponentIdentifier("content-type"));
        assertTrue(params.containsComponentIdentifier("accept"));
        assertFalse(params.containsComponentIdentifier("not-present"));

        // Test containsComponentIdentifier(StringItem) - includes parameters
        assertTrue(params.containsComponentIdentifier(StringItem.valueOf("content-type")));
        assertTrue(params.containsComponentIdentifier(itemWithParams));

        // A StringItem with same value but different parameters should not match
        assertFalse(params.containsComponentIdentifier(StringItem.valueOf("accept")));
    }

    private void assertParams(String name, SignatureParameters params, String expected) {
        String actual = params.toComponentValue().serialize();
        assertEquals("Params failure - "+name, expected, actual);
    }
}