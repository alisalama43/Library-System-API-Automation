
package api.test.Auth;

import api.endpoints.AuthEndpoints;
import api.payloads.LoginRequest;
import api.payloads.RegisterRequest;
import api.utilites.RestAssuredLogFilter;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static io.restassured.RestAssured.given;

public class TokenLifecycleTests extends BaseTest {
    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }
    private RegisterRequest createAdminPayload() {
        String email = "token_test_" + UUID.randomUUID() + "@test.com";

        return new RegisterRequest(
                "Token Test Admin",
                email,
                "StrongPassword123"
        );
    }
    private long getTokenExpiration(String token) {

        String[] parts = token.split("\\.");

        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid JWT format");
        }

        String payload = new String(
                Base64.getUrlDecoder().decode(parts[1]),
                StandardCharsets.UTF_8
        );

        // Extract exp value
        String expValue = payload.replaceAll(
                ".*\"exp\"\\s*:\\s*(\\d+).*",
                "$1"
        );

        if (!expValue.matches("\\d+")) {
            throw new IllegalArgumentException(
                    "Token does not contain a valid exp claim"
            );
        }

        return Long.parseLong(expValue);
    }

    // 1. Token Expiration
    // Disabled because it may wait for a long time.
    @Test(enabled = false)
    public void expiredTokenShouldBeRejected() throws InterruptedException {

        // Generate a fresh token
        LoginRequest loginPayload = new LoginRequest(
                "admin@library.com",
                "Admin@12345"
        );

        Response loginResponse = AuthEndpoints.login(loginPayload);

        Assert.assertEquals(loginResponse.statusCode(), 200);

        String freshToken = loginResponse.jsonPath().getString("token");

        long expirationTime = getTokenExpiration(freshToken);

        long currentTime = System.currentTimeMillis() / 1000;

        long remainingSeconds = expirationTime - currentTime;

        Assert.assertTrue(
                remainingSeconds > 0,
                "Token is already expired"
        );

        System.out.println(
                "Waiting " + remainingSeconds + " seconds for expiration..."
        );

        // Wait until token expires
        while (System.currentTimeMillis() / 1000 < expirationTime) {
            Thread.sleep(1000);
        }

        Response response = AuthEndpoints.adminRegister(
                createAdminPayload(),
                freshToken
        );

        Assert.assertEquals(
                response.statusCode(),
                401,
                "Expired token should be rejected"
        );
    }

    // 2. Token Reuse
    // Use the same valid token in multiple requests
    @Test
    public void tokenReuseTest() {

        for (int i = 0; i < 5; i++) {

            Response response = AuthEndpoints.adminRegister(
                    createAdminPayload(),
                    adminToken
            );

            System.out.println(
                    "Request " + (i + 1) +
                            " Status: " + response.statusCode()
            );

            Assert.assertTrue(
                    response.statusCode() == 200 ||
                            response.statusCode() == 201,
                    "Valid token should remain usable"
            );
        }}
    @Test
    public void tokenTamperingTest() {

        String[] parts = clientToken.split("\\.");

        Assert.assertEquals(parts.length, 3);

        String modifiedPayload = parts[1] + "AA";

        String tamperedToken =
                parts[0] + "." + modifiedPayload + "." + parts[2];

        Response response = AuthEndpoints.adminRegister(
                createAdminPayload(),
                tamperedToken
        );

        Assert.assertTrue(
                response.statusCode() == 401 ||
                        response.statusCode() == 403,
                "Tampered token must be rejected"
        );
    }

    // 4. Token Signature Validation
    // Change the signature part only
    @Test
    public void tokenSignatureValidationTest() {

        String[] parts = adminToken.split("\\.");

        Assert.assertEquals(parts.length, 3);

        String modifiedSignature =
                parts[2].substring(0, parts[2].length() - 1) + "A";

        String invalidSignatureToken =
                parts[0] + "." + parts[1] + "." + modifiedSignature;

        Response response = AuthEndpoints.adminRegister(
                createAdminPayload(),
                invalidSignatureToken
        );

        Assert.assertTrue(
                response.statusCode() == 401 ||
                        response.statusCode() == 403,
                "Invalid JWT signature must be rejected"
        );
    }

    // 5. Token Replay
    // Send the same request twice using the same token
    @Test
    public void tokenReplayTest() {

        RegisterRequest payload = createAdminPayload();

        Response firstResponse = AuthEndpoints.adminRegister(
                payload,
                adminToken
        );

        Response secondResponse = AuthEndpoints.adminRegister(
                payload,
                adminToken
        );

        System.out.println(
                "First Request: " + firstResponse.statusCode()
        );

        System.out.println(
                "Replay Request: " + secondResponse.statusCode()
        );

        // The second request may succeed or return conflict,
        // depending on the API's replay/idempotency policy.
        Assert.assertTrue(
                secondResponse.statusCode() == 200 ||
                        secondResponse.statusCode() == 201 ||
                        secondResponse.statusCode() == 409,
                "Unexpected replay response"
        );
    }

    // 6. Concurrent Token Usage
    // Use one token from multiple threads
    @Test
    public void concurrentTokenUsageTest() throws Exception {

        int numberOfRequests = 20;

        ExecutorService executor =
                Executors.newFixedThreadPool(numberOfRequests);

        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < numberOfRequests; i++) {

            Callable<Integer> task = () -> {

                Response response = AuthEndpoints.adminRegister(
                        createAdminPayload(),
                        adminToken
                );

                return response.statusCode();
            };

            futures.add(executor.submit(task));
        }

        int successfulRequests = 0;
        int failedRequests = 0;

        for (Future<Integer> future : futures) {

            int statusCode = future.get();

            System.out.println("Status: " + statusCode);

            if (statusCode == 200 || statusCode == 201) {
                successfulRequests++;
            } else {
                failedRequests++;
            }
        }

        executor.shutdown();

        System.out.println("Successful: " + successfulRequests);
        System.out.println("Failed: " + failedRequests);

        Assert.assertEquals(
                failedRequests,
                0,
                "Concurrent requests using a valid token should work"
        );
    }

    // 7. Token Isolation / Role Isolation
    // A client token must not gain admin privileges
    @Test
    public void clientTokenCannotAccessAdminEndpoint() {

        Response response = AuthEndpoints.adminRegister(
                createAdminPayload(),
                clientToken
        );

        Assert.assertEquals(
                response.statusCode(),
                403,
                "Client token must not access Admin-only endpoint"
        );
    }
}
