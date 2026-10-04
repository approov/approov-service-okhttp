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
            assertEquals("fetchCustomJWT: SDK not initialized", e.getMessage());
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
        try {
            ApproovService.getSubstitutionHeaders();
            fail("Expected IllegalStateException for getSubstitutionHeaders");
        } catch (IllegalStateException e) {
            assertEquals("ApproovService is not initialized", e.getMessage());
        }

        try {
            ApproovService.getSubstitutionQueryParams();
            fail("Expected IllegalStateException for getSubstitutionQueryParams");
        } catch (IllegalStateException e) {
            assertEquals("ApproovService is not initialized", e.getMessage());
        }

        try {
            ApproovService.getExclusionURLRegexs();
            fail("Expected IllegalStateException for getExclusionURLRegexs");
        } catch (IllegalStateException e) {
            assertEquals("ApproovService is not initialized", e.getMessage());
        }
    }
}

