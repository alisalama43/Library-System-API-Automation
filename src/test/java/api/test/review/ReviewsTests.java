package api.test.review;

import api.endpoints.AuthEndpoints;
import api.endpoints.ReviewsEndpoints;
import api.endpoints.Routes;
import api.payloads.LoginRequest;
import api.payloads.RegisterRequest;
import api.payloads.ReviewRequest;
import api.test.Auth.BaseTest;
import api.utilites.RestAssuredLogFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static io.restassured.RestAssured.given;

public class ReviewsTests extends BaseTest {
    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }

    private ObjectMapper mapper;
    private JsonNode rootNode;

    private String authorToken;
    private String borrowerToken;
    private String otherClientToken;
    private String noHistoryUserToken;

    private final Map<Integer, Integer> bookIdMap = new HashMap<>();
    private int createdReviewId;
    private int otherClientReviewId;

    @BeforeClass(alwaysRun = true)
    public void setup() throws IOException {
        mapper = new ObjectMapper();
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("testData/reviewsData.json");
        if (inputStream == null) {
            throw new RuntimeException("testData/reviewsData.json not found");
        }
        rootNode = mapper.readTree(inputStream);

        if (adminToken == null || adminToken.isEmpty()) {
            Response adminRes = AuthEndpoints.login(new LoginRequest("admin@library.com", "Admin@12345"));
            Assert.assertEquals(adminRes.statusCode(), 200);
            adminToken = adminRes.jsonPath().getString("token");
        }

        long ts = System.currentTimeMillis();

        authorToken = registerUser("Author User", "author_" + ts + "@test.com", "secret123");
        borrowerToken = registerUser("Borrower User", "borrower_" + ts + "@test.com", "secret123");
        otherClientToken = registerUser("Other Client", "other_" + ts + "@test.com", "secret123");
        noHistoryUserToken = registerUser("No History User", "nohist_" + ts + "@test.com", "secret123");

        for (int logicalId : new int[]{1, 2, 3, 4, 5, 6}) {
            int realBookId = createBookWithStock(adminToken, 5);
            bookIdMap.put(logicalId, realBookId);

            if (logicalId == 1 || logicalId == 3 || logicalId == 4 || logicalId == 5) {
                purchaseBook(authorToken, realBookId);
            } else if (logicalId == 6) {
                borrowBook(borrowerToken, realBookId);
            }
        }

        int book1RealId = resolveBookId(1);
        purchaseBook(otherClientToken, book1RealId);
        Response seedReview = ReviewsEndpoints.createReview(
                "{\"bookId\":" + book1RealId + ",\"rating\":5,\"comment\":\"Seed review for public get and average\"}",
                otherClientToken
        );
        Assert.assertEquals(seedReview.statusCode(), 201);
        otherClientReviewId = seedReview.jsonPath().getInt("id");
    }

    private String registerUser(String fullName, String email, String password) {
        Response res = AuthEndpoints.register(new RegisterRequest(fullName, email, password));
        Assert.assertTrue(res.statusCode() == 200 || res.statusCode() == 201);
        return res.jsonPath().getString("token");
    }

    private int createBookWithStock(String token, int copies) {
        long uniqueIsbn = Math.abs(System.nanoTime() % 1_000_000_000L);
        Map<String, Object> bookBody = new HashMap<>();
        bookBody.put("isbn", uniqueIsbn);
        bookBody.put("title", "Review Test Book " + uniqueIsbn);
        bookBody.put("author", "Robert C Martin");
        bookBody.put("publicationYear", 2024);
        bookBody.put("publisher", "Tech Press");
        bookBody.put("genre", "Software Engineering");
        bookBody.put("price", 100.0);
        bookBody.put("description", "Book created for automated review tests");

        Response bookRes = given()
                .header("Authorization", "Bearer " + token)
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
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(invBody)
                .when()
                .post(Routes.BASE_URL + "/api/inventory");

        Assert.assertTrue(invRes.statusCode() == 201 || invRes.statusCode() == 200);
        return bookId;
    }

    private void purchaseBook(String token, int bookId) {
        String orderJson = "{\"items\":[{\"bookId\":" + bookId + ",\"quantity\":1}]}";
        Response orderRes = given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(orderJson)
                .when()
                .post(Routes.BASE_URL + "/api/orders");

        Assert.assertEquals(orderRes.statusCode(), 201);
    }

    private void borrowBook(String token, int bookId) {
        String borrowJson = "{\"bookId\":" + bookId + "}";
        Response borrowRes = given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(borrowJson)
                .when()
                .post(Routes.BASE_URL + "/api/borrows");

        Assert.assertTrue(borrowRes.statusCode() == 201 || borrowRes.statusCode() == 200);
    }

    private String getTokenByRole(String role) {
        if (role == null) return "";
        switch (role) {
            case "VERIFIED_BUYER_OR_BORROWER":
            case "REVIEW_AUTHOR":
                return authorToken;
            case "VERIFIED_BORROWER_ONLY":
                return borrowerToken;
            case "OTHER_CLIENT":
                return otherClientToken;
            case "ADMIN":
                return adminToken;
            case "USER_WITHOUT_BOOK_HISTORY":
            case "NEW_USER_NO_REVIEWS":
                return noHistoryUserToken;
            case "NO_TOKEN":
            default:
                return "";
        }
    }

    private int resolveBookId(int jsonBookId) {
        return bookIdMap.getOrDefault(jsonBookId, jsonBookId);
    }

    private void assertTestCase(JsonNode testCase, Response response) {
        int expectedStatus = testCase.get("expectedStatus").asInt();
        Assert.assertEquals(response.getStatusCode(), expectedStatus);
    }

    private Object[][] getDataFromSection(String sectionName) {
        JsonNode section = rootNode.get(sectionName);
        if (section == null || !section.isArray()) {
            return new Object[0][0];
        }
        Object[][] data = new Object[section.size()][1];
        Iterator<JsonNode> elements = section.elements();
        int i = 0;
        while (elements.hasNext()) {
            data[i++][0] = elements.next();
        }
        return data;
    }

    @DataProvider(name = "getBookReviewsData")
    public Object[][] getBookReviewsData() {
        return getDataFromSection("getBookReviews");
    }

    @Test(priority = 1, dataProvider = "getBookReviewsData", groups = {"regression"})
    public void testGetReviewsByBookId(JsonNode testCase) {
        int bookId = resolveBookId(testCase.get("bookId").asInt());
        Response response = ReviewsEndpoints.getReviewsByBookId(bookId);
        assertTestCase(testCase, response);
    }

    @DataProvider(name = "getAverageRatingData")
    public Object[][] getAverageRatingData() {
        return getDataFromSection("getBookAverageRating");
    }

    @Test(priority = 2, dataProvider = "getAverageRatingData", groups = {"regression"})
    public void testGetBookReviewsAverage(JsonNode testCase) {
        int bookId = resolveBookId(testCase.get("bookId").asInt());
        Response response = ReviewsEndpoints.getBookReviewsAverage(bookId);
        assertTestCase(testCase, response);

        if (testCase.has("expectedAverageRating")) {
            String avg = response.jsonPath().getString("averageRating");
            Assert.assertEquals(avg, testCase.get("expectedAverageRating").asText());
        }
    }

    @DataProvider(name = "createReviewData")
    public Object[][] createReviewData() {
        return getDataFromSection("createReview");
    }

    @Test(priority = 3, dataProvider = "createReviewData", groups = {"regression"})
    public void testCreateReview(JsonNode testCase) {
        String token = getTokenByRole(testCase.get("userRole").asText());
        int expectedStatus = testCase.get("expectedStatus").asInt();
        JsonNode payloadNode = testCase.get("payload");

        ObjectNode requestBody = mapper.createObjectNode();
        if (payloadNode.has("bookId")) {
            if (payloadNode.get("bookId").isNull()) {
                requestBody.putNull("bookId");
            } else {
                requestBody.put("bookId", resolveBookId(payloadNode.get("bookId").asInt()));
            }
        }

        if (payloadNode.has("rating")) {
            if (payloadNode.get("rating").isNull()) {
                requestBody.putNull("rating");
            } else {
                requestBody.put("rating", payloadNode.get("rating").asInt());
            }
        }

        if (payloadNode.has("comment")) {
            if (payloadNode.get("comment").isNull()) {
                requestBody.putNull("comment");
            } else {
                requestBody.put("comment", payloadNode.get("comment").asText());
            }
        } else if (payloadNode.has("commentLength")) {
            int length = payloadNode.get("commentLength").asInt();
            requestBody.put("comment", "a".repeat(length));
        }

        Response response = ReviewsEndpoints.createReview(requestBody.toString(), token);
        assertTestCase(testCase, response);

        if (expectedStatus == 201 && createdReviewId == 0) {
            createdReviewId = response.jsonPath().getInt("id");
        }
    }

    @DataProvider(name = "getMyReviewsData")
    public Object[][] getMyReviewsData() {
        return getDataFromSection("getMyReviews");
    }

    @Test(priority = 4, dataProvider = "getMyReviewsData", groups = {"regression"})
    public void testGetMyReviews(JsonNode testCase) {
        String token = getTokenByRole(testCase.get("userRole").asText());
        Response response = ReviewsEndpoints.getMyReviews(token);
        assertTestCase(testCase, response);
    }

    @DataProvider(name = "updateReviewData")
    public Object[][] updateReviewData() {
        return getDataFromSection("updateReview");
    }

    @Test(priority = 5, dataProvider = "updateReviewData", groups = {"regression"})
    public void testUpdateReview(JsonNode testCase) {
        String token = getTokenByRole(testCase.get("userRole").asText());
        JsonNode payloadNode = testCase.get("payload");

        int jsonReviewId = testCase.get("reviewId").asInt();
        int targetId = (jsonReviewId == 999999) ? jsonReviewId : createdReviewId;

        Integer rating = (payloadNode.has("rating") && !payloadNode.get("rating").isNull())
                ? payloadNode.get("rating").asInt() : null;
        String comment = (payloadNode.has("comment") && !payloadNode.get("comment").isNull())
                ? payloadNode.get("comment").asText() : null;
        ReviewRequest payload = new ReviewRequest(rating, comment);

        Response response = ReviewsEndpoints.updateReview(targetId, payload, token);
        assertTestCase(testCase, response);
    }

    @DataProvider(name = "deleteReviewData")
    public Object[][] deleteReviewData() {
        return getDataFromSection("deleteReview");
    }

    @Test(priority = 6, dataProvider = "deleteReviewData", groups = {"regression"})
    public void testDeleteReview(JsonNode testCase) {
        String role = testCase.get("userRole").asText();
        String token = getTokenByRole(role);
        int expectedStatus = testCase.get("expectedStatus").asInt();

        int jsonReviewId = testCase.get("reviewId").asInt();
        int targetId = (jsonReviewId == 999999) ? jsonReviewId : createdReviewId;

        if ("ADMIN".equals(role) && expectedStatus == 204 && createdReviewId == -1) {
            int book1RealId = resolveBookId(1);
            String newReviewBody = "{\"bookId\":" + book1RealId + ",\"rating\":4,\"comment\":\"Review for admin deletion\"}";
            Response createRes = ReviewsEndpoints.createReview(newReviewBody, authorToken);
            if (createRes.statusCode() == 201) {
                targetId = createRes.jsonPath().getInt("id");
            }
        }

        Response response = ReviewsEndpoints.deleteReview(targetId, token);
        assertTestCase(testCase, response);

        if (expectedStatus == 204 && targetId == createdReviewId) {
            createdReviewId = -1;
        }
    }

    @Test(priority = 7, groups = {"security-audit"})
    public void testHttpMethodOverrideBypassProtection() {
        Response overrideDeleteRes = given()
                .header("Authorization", "Bearer " + noHistoryUserToken)
                .header("X-HTTP-Method-Override", "DELETE")
                .header("X-Method-Override", "DELETE")
                .header("X-Original-HTTP-Method", "DELETE")
                .contentType(ContentType.JSON)
                .when()
                .post(Routes.BASE_URL + "/api/reviews/" + otherClientReviewId);

        int status1 = overrideDeleteRes.statusCode();
        Assert.assertTrue(status1 == 405 || status1 == 403 || status1 == 404);

        Response overridePutRes = given()
                .header("Authorization", "Bearer " + noHistoryUserToken)
                .header("X-HTTP-Method-Override", "PUT")
                .contentType(ContentType.JSON)
                .body("{\"rating\":1,\"comment\":\"Hacked via method override\"}")
                .when()
                .post(Routes.BASE_URL + "/api/reviews/" + otherClientReviewId);

        int status2 = overridePutRes.statusCode();
        Assert.assertTrue(status2 == 405 || status2 == 403 || status2 == 404);

        Response verifyRes = ReviewsEndpoints.getReviewsByBookId(resolveBookId(1));
        Assert.assertEquals(verifyRes.statusCode(), 200);
        Assert.assertTrue(verifyRes.asString().contains("Seed review for public get and average"));
    }

    @Test(priority = 8, groups = {"security-audit"})
    public void testUnsupportedHttpMethodsReturn405() {
        Response deleteCollectionRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .when()
                .delete(Routes.BASE_URL + "/api/reviews");
        Assert.assertEquals(deleteCollectionRes.statusCode(), 405);

        Response putCollectionRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body("{\"rating\":5}")
                .when()
                .put(Routes.BASE_URL + "/api/reviews");
        Assert.assertEquals(putCollectionRes.statusCode(), 405);

        Response postMyRes = given()
                .header("Authorization", "Bearer " + authorToken)
                .contentType(ContentType.JSON)
                .body("{\"rating\":5}")
                .when()
                .post(Routes.BASE_URL + "/api/reviews/my");
        Assert.assertEquals(postMyRes.statusCode(), 405);
    }

    @Test(priority = 9, groups = {"security-audit"})
    public void testXssAndSqlInjectionPayloadsRejectedCleanly() {
        int book1RealId = resolveBookId(1);
        String[] maliciousPayloads = {
                "<script>alert('XSS')</script>",
                "' OR '1'='1' --",
                "\"; DROP TABLE reviews; --"
        };

        for (String payload : maliciousPayloads) {
            Map<String, Object> body = new HashMap<>();
            body.put("bookId", book1RealId);
            body.put("rating", 5);
            body.put("comment", payload);

            Response res = ReviewsEndpoints.createReview(body.toString(), noHistoryUserToken);
            Assert.assertNotEquals(res.statusCode(), 500);
            Assert.assertEquals(res.statusCode(), 400);
        }
    }

    @Test(priority = 10, groups = {"security-audit"})
    public void testMassAssignmentCannotSpoofAuthorOrReviewId() {
        int book2RealId = resolveBookId(2);
        purchaseBook(noHistoryUserToken, book2RealId);

        String spoofedPayload = "{"
                + "\"id\": 999888,"
                + "\"bookId\": " + book2RealId + ","
                + "\"rating\": 5,"
                + "\"comment\": \"Testing mass assignment protection\","
                + "\"client\": {\"id\": 1, \"role\": \"ADMIN\"}"
                + "}";

        Response res = ReviewsEndpoints.createReview(spoofedPayload, noHistoryUserToken);
        Assert.assertEquals(res.statusCode(), 201);

        int actualId = res.jsonPath().getInt("id");
        String actualEmail = res.jsonPath().getString("client.email");

        Assert.assertNotEquals(actualId, 999888);
        Assert.assertTrue(actualEmail != null && actualEmail.startsWith("nohist_"));

        ReviewsEndpoints.deleteReview(actualId, noHistoryUserToken);
    }
    @Test(priority = 3, dependsOnMethods = "testCreateReview", groups = {"regression"})
    public void testAverageRatingMathematicalAccuracy() {
        int book1RealId = resolveBookId(1);
        Response response = ReviewsEndpoints.getBookReviewsAverage(book1RealId);

        Assert.assertEquals(response.statusCode(), 200);
        double avg = response.jsonPath().getDouble("averageRating");
        Assert.assertEquals(avg, 5.0, 0.01);
    }

    @Test(priority = 11, groups = {"regression", "validation"})
    public void testNullRatingAndMalformedTypesReturn400() {
        int book1RealId = resolveBookId(1);

        Response nullRatingPost = ReviewsEndpoints.createReview(
                "{\"bookId\":" + book1RealId + ",\"rating\":null,\"comment\":\"Valid comment\"}",
                authorToken
        );
        Assert.assertEquals(nullRatingPost.statusCode(), 400);

        Response invalidTypePost = ReviewsEndpoints.createReview(
                "{\"bookId\":\"invalid\",\"rating\":\"five\",\"comment\":\"Valid comment\"}",
                authorToken
        );
        Assert.assertEquals(invalidTypePost.statusCode(), 400);

        Response emptyBodyPut = ReviewsEndpoints.updateReview(
                otherClientReviewId,
                new ReviewRequest(null, "Updated without rating"),
                otherClientToken
        );
        Assert.assertEquals(emptyBodyPut.statusCode(), 400);
    }

    @Test(priority = 12, groups = {"security-audit"})
    public void testConcurrentDuplicateReviewsNeverBypassOneReviewRule() throws InterruptedException {
        int bookId = createBookWithStock(adminToken, 5);
        purchaseBook(borrowerToken, bookId);

        int threads = 4;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                Response res = ReviewsEndpoints.createReview(
                        "{\"bookId\":" + bookId + ",\"rating\":5,\"comment\":\"Concurrent review test\"}",
                        borrowerToken
                );
                return res.statusCode();
            }));
        }

        latch.countDown();

        int createdCount = 0;
        for (java.util.concurrent.Future<Integer> f : futures) {
            try {
                if (f.get() == 201) {
                    createdCount++;
                }
            } catch (Exception e) {
                Assert.fail(e.getMessage());
            }
        }
        executor.shutdown();

        Assert.assertEquals(createdCount, 1);
    }
}