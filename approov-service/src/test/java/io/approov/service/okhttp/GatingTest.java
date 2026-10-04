package io.approov.service.okhttp;
import org.junit.Test;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import androidx.test.core.app.ApplicationProvider;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class GatingTest {
    @Before
    @After
    public void resetApproovServiceState() {
        ApproovService.reset();
    }

    @Test
    public void testFetchCustomJWTWithEmptyConfig() {
        ApproovService.initialize(ApplicationProvider.getApplicationContext(), "");
        try {
            ApproovService.fetchCustomJWT("{\"test\": 1}");
            fail("Expected ApproovException but none was thrown");
        } catch (ApproovException e) {
            assertEquals("fetchCustomJWT: Approov protection not enabled", e.getMessage());
        }
    }

    @Test
    public void testGetOkHttpClientBeforeInitialization() {
        // SPECIFICATION 5.7(f): the client is available before initialize and its
        // requests go out without Approov processing until protection is enabled
        assertNotNull(ApproovService.getOkHttpClient());
        assertSame(ApproovService.getOkHttpClient(), ApproovService.getOkHttpClient());
    }

    @Test
    public void testGetOkHttpClientWithEmptyConfig() {
        ApproovService.initialize(ApplicationProvider.getApplicationContext(), "");
        assertNotNull(ApproovService.getOkHttpClient());
    }

    @Test
    public void testInitializationWithEmptyConfigAndNullComment() {
        ApproovService.initialize(ApplicationProvider.getApplicationContext(), "", null);
        assertTrue(ApproovService.isApproovServiceEnabled());
        assertFalse(ApproovService.isApproovProtectionEnabled());
    }

    @Test
    public void testGetMethodsBeforeInitialization() {
        // SPECIFICATION 5.7(b): configuration may be read and set before initialize
        assertTrue(ApproovService.getSubstitutionHeaders().isEmpty());
        assertTrue(ApproovService.getSubstitutionQueryParams().isEmpty());
        assertTrue(ApproovService.getExclusionURLRegexs().isEmpty());
        ApproovService.addSubstitutionHeader("Api-Key", null);
        ApproovService.addSubstitutionQueryParam("api_key");
        ApproovService.addExclusionURLRegex("^https://excluded\\.example\\.com/");
        assertEquals(1, ApproovService.getSubstitutionHeaders().size());
        assertEquals(1, ApproovService.getSubstitutionQueryParams().size());
        assertEquals(1, ApproovService.getExclusionURLRegexs().size());
    }
}

