package io.approov.service.okhttp;
import org.junit.Test;
import org.junit.After;
import org.junit.Before;
import java.lang.reflect.Field;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import androidx.test.core.app.ApplicationProvider;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class GatingTest {
    @Before
    @After
    public void resetApproovServiceState() throws Exception {
        Field isInitializedField = ApproovService.class.getDeclaredField("isInitialized");
        isInitializedField.setAccessible(true);
        isInitializedField.setBoolean(null, false);
    }

    @Test
    public void testFetchCustomJWTWithEmptyConfig() throws Exception {
        ApproovService.initialize(ApplicationProvider.getApplicationContext(), "");
        try {
            ApproovService.fetchCustomJWT("{\"test\": 1}");
            System.out.println("fetchCustomJWT SUCCESS");
        } catch (ApproovException e) {
            System.out.println("fetchCustomJWT THREW: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.out);
        } catch (Exception e) {
            System.out.println("fetchCustomJWT THREW OTHER: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.out);
        }
    }
}
