
package api.test.Auth;

import api.endpoints.AuthEndpoints;
import api.payloads.LoginRequest;
import api.utilites.RestAssuredLogFilter;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.*;

import static io.restassured.RestAssured.given;

public class SessionManagementTests extends BaseTest {
    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }

    private Response protectedRequest(String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get(api.endpoints.Routes.ADMIN_REGISTER);
    }


    @Test
    public void multipleActiveSessionsTest() {

        LoginRequest payload =
                new LoginRequest("admin@library.com", "Admin@12345");

        Response firstLogin = AuthEndpoints.login(payload);
        Response secondLogin = AuthEndpoints.login(payload);

        Assert.assertEquals(firstLogin.statusCode(), 200);
        Assert.assertEquals(secondLogin.statusCode(), 200);

        String firstToken = firstLogin.jsonPath().getString("token");
        String secondToken = secondLogin.jsonPath().getString("token");

        Assert.assertNotNull(firstToken);
        Assert.assertNotNull(secondToken);

        System.out.println("First and second login succeeded.");
        System.out.println("Both sessions received tokens.");
    }

    // 2. Session Independence
    @Test
    public void sessionIndependenceTest() {

        LoginRequest payload =
                new LoginRequest("admin@library.com", "Admin@12345");

        Response firstLogin = AuthEndpoints.login(payload);
        Response secondLogin = AuthEndpoints.login(payload);

        String firstToken = firstLogin.jsonPath().getString("token");
        String secondToken = secondLogin.jsonPath().getString("token");

        Assert.assertNotNull(firstToken);
        Assert.assertNotNull(secondToken);

        // Both tokens should be usable independently
        Response firstResponse = AuthEndpoints.adminRegister(
                new api.payloads.RegisterRequest(
                        "Session Test Admin",
                        "session_" + System.currentTimeMillis() + "@test.com",
                        "StrongPassword123"
                ),
                firstToken
        );

        Response secondResponse = AuthEndpoints.adminRegister(
                new api.payloads.RegisterRequest(
                        "Session Test Admin",
                        "session_" + (System.currentTimeMillis() + 1) + "@test.com",
                        "StrongPassword123"
                ),
                secondToken
        );

        Assert.assertTrue(
                firstResponse.statusCode() == 200 ||
                        firstResponse.statusCode() == 201
        );

        Assert.assertTrue(
                secondResponse.statusCode() == 200 ||
                        secondResponse.statusCode() == 201
        );
    }

    // 3. Old Token After New Login
    @Test
    public void oldTokenAfterNewLoginTest() {

        LoginRequest payload =
                new LoginRequest("admin@library.com", "Admin@12345");

        Response firstLogin = AuthEndpoints.login(payload);

        String oldToken = firstLogin.jsonPath().getString("token");

        Response secondLogin = AuthEndpoints.login(payload);

        String newToken = secondLogin.jsonPath().getString("token");

        Assert.assertEquals(firstLogin.statusCode(), 200);
        Assert.assertEquals(secondLogin.statusCode(), 200);

        Assert.assertNotNull(oldToken);
        Assert.assertNotNull(newToken);

        // Old token should remain usable if the API does not revoke it
        Response oldTokenResponse = AuthEndpoints.adminRegister(
                new api.payloads.RegisterRequest(
                        "Old Token Test",
                        "old_" + System.currentTimeMillis() + "@test.com",
                        "StrongPassword123"
                ),
                oldToken
        );

        Assert.assertTrue(
                oldTokenResponse.statusCode() == 200 ||
                        oldTokenResponse.statusCode() == 201,
                "Old token was rejected after a new login."
        );
    }

    // 4. Concurrent Token Usage
    @Test
    public void concurrentTokenUsageTest() throws Exception {

        ExecutorService executor = Executors.newFixedThreadPool(5);

        Callable<Response> task = () ->
                AuthEndpoints.adminRegister(
                        new api.payloads.RegisterRequest(
                                "Concurrent User",
                                "concurrent_" + System.nanoTime() + "@test.com",
                                "StrongPassword123"
                        ),
                        adminToken
                );

        Future<Response>[] results = new Future[5];

        for (int i = 0; i < 5; i++) {
            results[i] = executor.submit(task);
        }

        for (Future<Response> result : results) {
            Response response = result.get();

            Assert.assertTrue(
                    response.statusCode() == 200 ||
                            response.statusCode() == 201,
                    "Concurrent request failed: " + response.statusCode()
            );
        }

        executor.shutdown();
    }
}
