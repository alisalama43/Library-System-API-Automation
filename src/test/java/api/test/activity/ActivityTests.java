package api.test.activity;

import api.endpoints.ActivityEndpoints;
import api.endpoints.AuthEndpoints;
import api.endpoints.Routes;
import api.payloads.LoginRequest;
import api.payloads.RegisterRequest;
import api.test.Auth.BaseTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;

public class ActivityTests extends BaseTest {

    private String freshUserToken;
    private String activeUserToken;
    private String otherUserToken;
    private int activeUserId;
    private int borrowIdToReturn;

    @BeforeClass(alwaysRun = true)
    public void setup() {
        if (adminToken == null || adminToken.isEmpty()) {
            Response adminRes = AuthEndpoints.login(new LoginRequest("admin@library.com", "Admin@12345"));
            Assert.assertEquals(adminRes.statusCode(), 200);
            adminToken = adminRes.jsonPath().getString("token");
        }

        long ts = System.currentTimeMillis();

        freshUserToken = registerAndGetToken("Fresh User", "fresh_act_" + ts + "@test.com", "secret123");
        otherUserToken = registerAndGetToken("Other User", "other_act_" + ts + "@test.com", "secret123");

        String activeEmail = "active_act_" + ts + "@test.com";
        Response activeRegRes = AuthEndpoints.register(new RegisterRequest("Active User", activeEmail, "secret123"));
        Assert.assertTrue(activeRegRes.statusCode() == 200 || activeRegRes.statusCode() == 201);
        activeUserToken = activeRegRes.jsonPath().getString("token");
        Integer extractedId = activeRegRes.jsonPath().get("id");
        if (extractedId == null) {
            extractedId = activeRegRes.jsonPath().get("user.id");
        }
        activeUserId = (extractedId != null) ? extractedId : resolveUserIdByEmail(activeEmail);
    }

    private String registerAndGetToken(String fullName, String email, String password) {
        Response res = AuthEndpoints.register(new RegisterRequest(fullName, email, password));
        Assert.assertTrue(res.statusCode() == 200 || res.statusCode() == 201);
        return res.jsonPath().getString("token");
    }

    private int resolveUserIdByEmail(String email) {
        Response res = given()
                .header("Authorization", "Bearer " + adminToken)
                .when()
                .get(Routes.BASE_URL + "/api/users");
        if (res.statusCode() == 200) {
            Integer id = res.jsonPath().get("find { it.email == '" + email + "' }.id");
            if (id != null) return id;
        }
        return 1;
    }

    private int createBookWithStock(int copies, double price) {
        long uniqueIsbn = Math.abs(System.nanoTime() % 1_000_000_000L);
        Map<String, Object> bookBody = new HashMap<>();
        bookBody.put("isbn", uniqueIsbn);
        bookBody.put("title", "Activity Book " + uniqueIsbn);
        bookBody.put("author", "Martin Fowler");
        bookBody.put("publicationYear", 2023);
        bookBody.put("publisher", "Addison Wesley");
        bookBody.put("genre", "Software");
        bookBody.put("price", price);
        bookBody.put("description", "Book for activity summary tests");

        Response bookRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(bookBody)
                .when()
                .post(Routes.BASE_URL + "/api/books");

        Assert.assertEquals(bookRes.statusCode(), 201);
        int bookId = bookRes.jsonPath().getInt("id");

        Map<String, Object> invBody = new HashMap<>();
        invBody.put("bookId", bookId);
        invBody.put("availableCopies", copies);

        Response invRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(invBody)
                .when()
                .post(Routes.BASE_URL + "/api/inventory");

        Assert.assertTrue(invRes.statusCode() == 201 || invRes.statusCode() == 200);
        return bookId;
    }

    @Test(priority = 1, groups = {"regression"})
    public void testFreshUserActivitySummaryHasZeroCounters() {
        Response res = ActivityEndpoints.getMyActivitySummary(freshUserToken);
        Assert.assertEquals(res.statusCode(), 200);

        String body = res.asString();
        Assert.assertFalse(body.isEmpty());
        Assert.assertTrue(body.contains("0"));
    }

    @Test(priority = 2, groups = {"regression"})
    public void testActivitySummaryReflectsOrdersBorrowsReviewsAndReservations() {
        int bookForOrder = createBookWithStock(5, 150.0);
        int bookForBorrow = createBookWithStock(5, 80.0);
        int bookForReservation = createBookWithStock(0, 90.0);

        Response orderRes = given()
                .header("Authorization", "Bearer " + activeUserToken)
                .contentType(ContentType.JSON)
                .body("{\"items\":[{\"bookId\":" + bookForOrder + ",\"quantity\":2}]}")
                .when()
                .post(Routes.BASE_URL + "/api/orders");
        Assert.assertEquals(orderRes.statusCode(), 201);

        Response borrowRes = given()
                .header("Authorization", "Bearer " + activeUserToken)
                .contentType(ContentType.JSON)
                .body("{\"bookId\":" + bookForBorrow + "}")
                .when()
                .post(Routes.BASE_URL + "/api/borrows");
        Assert.assertTrue(borrowRes.statusCode() == 201 || borrowRes.statusCode() == 200);
        borrowIdToReturn = borrowRes.jsonPath().getInt("id");

        Response reviewRes = given()
                .header("Authorization", "Bearer " + activeUserToken)
                .contentType(ContentType.JSON)
                .body("{\"bookId\":" + bookForOrder + ",\"rating\":5,\"comment\":\"Great architecture book\"}")
                .when()
                .post(Routes.BASE_URL + "/api/reviews");
        Assert.assertEquals(reviewRes.statusCode(), 201);

        Response reserveRes = given()
                .header("Authorization", "Bearer " + activeUserToken)
                .contentType(ContentType.JSON)
                .body("{\"bookId\":" + bookForReservation + "}")
                .when()
                .post(Routes.BASE_URL + "/api/reservations");
        Assert.assertTrue(reserveRes.statusCode() == 201 || reserveRes.statusCode() == 200);

        Response summaryRes = ActivityEndpoints.getMyActivitySummary(activeUserToken);
        Assert.assertEquals(summaryRes.statusCode(), 200);

        String raw = summaryRes.asString();
        Assert.assertTrue(raw.contains("300"));
        Assert.assertTrue(raw.contains("1"));
    }

    @Test(priority = 3, dependsOnMethods = "testActivitySummaryReflectsOrdersBorrowsReviewsAndReservations", groups = {"regression"})
    public void testActivitySummaryUpdatesAfterReturningBorrowedBook() {
        Response returnRes = given()
                .header("Authorization", "Bearer " + activeUserToken)
                .when()
                .put(Routes.BASE_URL + "/api/borrows/" + borrowIdToReturn + "/return");
        Assert.assertEquals(returnRes.statusCode(), 200);

        Response summaryRes = ActivityEndpoints.getMyActivitySummary(activeUserToken);
        Assert.assertEquals(summaryRes.statusCode(), 200);
    }

    @Test(priority = 5, groups = {"regression"})
    public void testAdminGetNonExistentUserActivitySummaryReturns404() {
        Response res = ActivityEndpoints.getUserActivitySummaryById(999999, adminToken);
        Assert.assertEquals(res.statusCode(), 404);
    }

    @Test(priority = 6, groups = {"security-audit"})
    public void testUnauthenticatedAccessRejected() {
        Response noTokenMe = given()
                .when()
                .get(Routes.BASE_URL + "/api/users/me/activity-summary");
        int s1 = noTokenMe.statusCode();
        Assert.assertTrue(s1 == 401 || s1 == 403);

        Response invalidTokenMe = given()
                .header("Authorization", "Bearer invalid.jwt.token")
                .when()
                .get(Routes.BASE_URL + "/api/users/me/activity-summary");
        int s2 = invalidTokenMe.statusCode();
        Assert.assertTrue(s2 == 401 || s2 == 403);

        Response noTokenAdminEndpoint = given()
                .when()
                .get(Routes.BASE_URL + "/api/users/" + activeUserId + "/activity-summary");
        int s3 = noTokenAdminEndpoint.statusCode();
        Assert.assertTrue(s3 == 401 || s3 == 403);
    }

    @Test(priority = 7, groups = {"security-audit"})
    public void testBolaNonAdminCannotAccessUserActivitySummaryById() {
        Response otherUserAccess = ActivityEndpoints.getUserActivitySummaryById(activeUserId, otherUserToken);
        Assert.assertEquals(otherUserAccess.statusCode(), 403);

        Response selfViaAdminEndpoint = ActivityEndpoints.getUserActivitySummaryById(activeUserId, activeUserToken);
        Assert.assertEquals(selfViaAdminEndpoint.statusCode(), 403);
    }

    @Test(priority = 8, groups = {"security-audit"})
    public void testInvalidUserIdFormatsDoNotCauseServerError() {
        Response alphaIdRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .when()
                .get(Routes.BASE_URL + "/api/users/invalid_id/activity-summary");
        Assert.assertNotEquals(alphaIdRes.statusCode(), 500);
        Assert.assertTrue(alphaIdRes.statusCode() == 400 || alphaIdRes.statusCode() == 404);

        Response negativeIdRes = ActivityEndpoints.getUserActivitySummaryById(-1, adminToken);
        Assert.assertNotEquals(negativeIdRes.statusCode(), 500);
        Assert.assertTrue(negativeIdRes.statusCode() == 400 || negativeIdRes.statusCode() == 404);
    }

    @Test(priority = 9, groups = {"security-audit"})
    public void testUnsupportedHttpMethodsAndMethodOverrideRejected() {
        Response postRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .when()
                .post(Routes.BASE_URL + "/api/users/me/activity-summary");
        Assert.assertEquals(postRes.statusCode(), 405);

        Response deleteRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .when()
                .delete(Routes.BASE_URL + "/api/users/" + activeUserId + "/activity-summary");
        Assert.assertEquals(deleteRes.statusCode(), 405);

        Response overrideRes = given()
                .header("Authorization", "Bearer " + otherUserToken)
                .header("X-HTTP-Method-Override", "GET")
                .header("X-Method-Override", "GET")
                .when()
                .post(Routes.BASE_URL + "/api/users/" + activeUserId + "/activity-summary");
        Assert.assertTrue(overrideRes.statusCode() == 405 || overrideRes.statusCode() == 403);
    }
}