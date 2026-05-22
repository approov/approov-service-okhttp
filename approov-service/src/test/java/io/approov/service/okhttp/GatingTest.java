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
        try {
            ApproovService.getOkHttpClient();
            fail("Expected IllegalStateException but none was thrown");
        } catch (IllegalStateException e) {
            assertEquals("getOkHttpClient: SDK not initialized", e.getMessage());
        }
    }

    @Test
    public void testGetOkHttpClientWithEmptyConfig() {
        ApproovService.initialize(ApplicationProvider.getApplicationContext(), "");
        assertNotNull(ApproovService.getOkHttpClient());
    }
}

