package api.test.Auth;

import api.endpoints.AuthEndpoints;
import api.payloads.LoginRequest;
import api.utilites.RestAssuredLogFilter;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.*;

import static io.restassured.RestAssured.given;

public class OWASPSecurityTests extends BaseTest {
    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }

    private static final String ADMIN_EMAIL = "admin@library.com";
    private static final String CLIENT_PASSWORD = "Test@12345";
    private static final String REGISTER_PATH = "/api/auth/register";
    private static final int RATE_LIMIT_ATTEMPTS = 30;

    private Response getRequest(String endpoint, String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get(endpoint);
    }

    private Response register(Map<String, ?> body) {
        return given()
                .contentType(ContentType.JSON)
                .body(body)
                .post(REGISTER_PATH);
    }

    /** Passes if the response status is one of the allowed codes. */
    private void assertStatusIn(Response response, String message, int... allowed) {
        int actual = response.statusCode();
        for (int code : allowed) {
            if (code == actual) {
                return;
            }
        }
        Assert.fail(message + " (actual status: " + actual + ")");
    }

    /** Registers a fresh CLIENT and returns its token. */
    private String createNewClientToken() {
        String email = "owasp_" + UUID.randomUUID() + "@test.com";

        Response registerResponse = register(Map.of(
                "fullName", "OWASP Test User",
                "email", email,
                "password", CLIENT_PASSWORD
        ));
        assertStatusIn(registerResponse, "Client registration failed", 200, 201);

        Response loginResponse = AuthEndpoints.login(new LoginRequest(email, CLIENT_PASSWORD));
        Assert.assertEquals(loginResponse.statusCode(), 200, "Login of the new client failed");

        String token = loginResponse.jsonPath().getString("token");
        Assert.assertNotNull(token, "Login response has no token");
        return token;
    }
    @Test(description = "API1 - client must not read another user's borrow record")
    public void bolaTest() {
        Response allBorrows = getRequest("/api/borrows", adminToken);
        Assert.assertEquals(allBorrows.statusCode(), 200, "Admin could not list borrows");

        Response myBorrows = getRequest("/api/borrows/my", clientToken);
        Assert.assertEquals(myBorrows.statusCode(), 200, "Client could not list own borrows");

        List<Integer> allIds = allBorrows.jsonPath().getList("id", Integer.class);
        Set<Integer> myIds = new HashSet<>(myBorrows.jsonPath().getList("id", Integer.class));

        Integer otherUserBorrowId = allIds.stream()
                .filter(id -> !myIds.contains(id))
                .findFirst()
                .orElseThrow(() -> new SkipException("No borrow record belonging to another user."));

        Response response = getRequest("/api/borrows/" + otherUserBorrowId, clientToken);

        assertStatusIn(response, "Client accessed another user's borrow record!", 403, 404);
    }
    @Test(description = "API2 - invalid JWT must be rejected")
    public void brokenAuthenticationTest() {
        Response response = getRequest("/api/orders/my", "invalid.fake.jwt");

        assertStatusIn(response, "Invalid JWT must be rejected", 401, 403);
    }
    @Test(description = "API3 - registration must not allow assigning the ADMIN role")
    public void massAssignmentTest() {
        String email = "mass_" + UUID.randomUUID() + "@test.com";

        Response registerResponse = register(Map.of(
                "fullName", "Mass Assignment User",
                "email", email,
                "password", CLIENT_PASSWORD,
                "role", "ADMIN"
        ));

        assertStatusIn(registerResponse, "Unexpected registration response", 200, 201, 400);

        // 400 = the API rejected the extra field, which is also a safe behaviour.
        if (registerResponse.statusCode() == 400) {
            return;
        }

        Response loginResponse = AuthEndpoints.login(new LoginRequest(email, CLIENT_PASSWORD));
        Assert.assertEquals(loginResponse.statusCode(), 200, "Login failed after registration");

        String role = loginResponse.jsonPath().getString("role");

        Assert.assertNotEquals(role, "ADMIN",
                "User must not assign ADMIN role through registration.");
    }

    @Test
    public void resourceConsumptionTest() {

        Map<Integer, Integer> statusCounts = new HashMap<>();

        for (int i = 1; i <= 30; i++) {

            Response response = AuthEndpoints.login(
                    new LoginRequest(
                            "wrong@email.com",
                            "WrongPassword123"
                    )
            );

            int status = response.statusCode();

            statusCounts.put(
                    status,
                    statusCounts.getOrDefault(status, 0) + 1
            );

            System.out.println(
                    "Attempt " + i + " => " + status
            );
        }

        System.out.println("========== SUMMARY ==========");

        statusCounts.forEach((status, count) ->
                System.out.println(
                        "Status " + status + " : " + count + " times"
                )
        );
    }

    @Test(description = "API6 - duplicate active reservation must be rejected")
    public void duplicateReservationTest() {
        Response inventoryResponse = given().when().get("/api/inventory");
        Assert.assertEquals(inventoryResponse.statusCode(), 200, "Could not load inventory");

        List<Integer> outOfStockBookIds = inventoryResponse.jsonPath()
                .getList("findAll { it.availableCopies == 0 }.book.id", Integer.class);

        if (outOfStockBookIds == null || outOfStockBookIds.isEmpty()) {
            throw new SkipException("No out-of-stock book available for reservation testing.");
        }

        int bookId = outOfStockBookIds.get(0);
        String freshToken = createNewClientToken();

        Response firstReservation = reserve(freshToken, bookId);
        Assert.assertEquals(firstReservation.statusCode(), 201, "First reservation should succeed.");

        int reservationId = firstReservation.jsonPath().getInt("id");

        try {
            Response secondReservation = reserve(freshToken, bookId);

            Assert.assertEquals(secondReservation.statusCode(), 409,
                    "Duplicate active reservation must be rejected.");
        } finally {
            given()
                    .header("Authorization", "Bearer " + freshToken)
                    .when()
                    .put("/api/reservations/" + reservationId + "/cancel");
        }
    }

    private Response reserve(String token, int bookId) {
        return given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post("/api/reservations");
    }

    @Test(description = "API8 - wrong route/method must not leak internal details")
    public void securityMisconfigurationTest() {
        Response response = given().when().get("/api/Auth/login");

        assertStatusIn(response, "Unexpected status for a wrong route", 401, 403, 404, 405);

        String body = response.asString().toLowerCase();

        Assert.assertFalse(body.contains("stacktrace"), "Server exposed stack trace.");
        Assert.assertFalse(body.contains("java.lang"), "Server exposed internal Java exception.");
        Assert.assertFalse(body.contains("sqlstate"), "Server exposed database error details.");
    }



}
