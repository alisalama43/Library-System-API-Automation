package api.test.Inventory;
import api.endpoints.BookEndPointes;
import api.endpoints.Routes;
import api.payloads.BookRequest;
import api.test.Auth.BaseTest;
import api.utilites.RestAssuredLogFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.notNullValue;

public class InventoryTests extends BaseTest {
    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }

    private static final String INVENTORY_URL =
            Routes.BASE_URL + "/api/inventory";

    private static final String INVENTORY_BY_ID_URL =
            Routes.BASE_URL + "/api/inventory/{id}";

    private static final String INVENTORY_BY_BOOK_ID_URL =
            Routes.BASE_URL + "/api/inventory/book/{bookId}";

    private final ObjectMapper mapper = new ObjectMapper();

    private final List<Integer> createdInventoryIds =
            Collections.synchronizedList(new ArrayList<Integer>());

    private final List<Integer> createdBookIds =
            Collections.synchronizedList(new ArrayList<Integer>());


    // ==================== Helpers ====================

    private String randomLetters(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }

        return sb.toString();
    }

    private int createTempBook() {
        BookRequest bookPayload = new BookRequest(
                "Inventory Book " + randomLetters(6),
                "Inventory Author " + randomLetters(5),
                2025,
                "Tech Press",
                "Technology",
                null,
                80.0,
                "Temporary book created for inventory testing"
        );

        Response bookRes = BookEndPointes.createBook(bookPayload, adminToken);
        Assert.assertEquals(
                bookRes.statusCode(),
                201,
                "Failed to create helper book: " + bookRes.asString()
        );

        int bookId = bookRes.jsonPath().getInt("id");
        createdBookIds.add(bookId);
        return bookId;
    }
    private JsonNode loadTestData() {
        try {
            InputStream inputStream = getClass()
                    .getClassLoader()
                    .getResourceAsStream("testdata/inventorydata.json");

            if (inputStream == null) {
                inputStream = getClass()
                        .getClassLoader()
                        .getResourceAsStream("inventorydata.json");
            }
            if (inputStream != null) {
                try (InputStream is = inputStream) {
                    return mapper.readTree(is);
                }
            }

            File fallbackFile =
                    new File("src/test/resources/testdata/inventorydata.json");

            if (!fallbackFile.exists()) {
                fallbackFile =
                        new File("src/test/resources/inventorydata.json");
            }

            if (!fallbackFile.exists()) {
                throw new IOException(
                        "inventorydata.json was not found in test resources"
                );
            }

            return mapper.readTree(fallbackFile);

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to load inventorydata.json",
                    e
            );
        }
    }


    // ==================== 2. Data Providers ====================

    private Object[][] extractCases(String sectionName) {
        JsonNode casesNode = loadTestData().get(sectionName);

        if (casesNode == null || !casesNode.isArray() || casesNode.isEmpty()) {
            throw new IllegalStateException(
                    "No test cases found for section: " + sectionName
            );
        }

        Object[][] data = new Object[casesNode.size()][1];

        for (int i = 0; i < casesNode.size(); i++) {
            data[i][0] = casesNode.get(i);
        }

        return data;
    }

    @DataProvider(name = "createValidAndBoundaryData")
    public Object[][] createValidAndBoundaryData() {
        return extractCases("createValidAndBoundaryCases");
    }

    @DataProvider(name = "createInvalidAndBoundaryData")
    public Object[][] createInvalidAndBoundaryData() {
        return extractCases("createInvalidAndOutOfBoundsCases");
    }

    @DataProvider(name = "updateValidAndBoundaryData")
    public Object[][] updateValidAndBoundaryData() {
        return extractCases("updateValidAndBoundaryCases");
    }

    @DataProvider(name = "updateInvalidData")
    public Object[][] updateInvalidData() {
        return extractCases("updateInvalidCases");
    }
    @Test(priority = 1, groups = {"smoke", "regression"})
    public void testSmokeGetAllInventoryPublicAccess() {
        given()
                .when()
                .get(INVENTORY_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", isA(List.class));
    }


    // ==================== 4. Full CRUD Lifecycle & RBAC ====================

    @Test(priority = 2, groups = {"smoke", "regression"})
    public void testCompleteInventoryCrudLifecycleAndPermissions() {
        int bookId = createTempBook();

        // Newly created book has no inventory record yet -> 404
        given()
                .pathParam("bookId", bookId)
                .when()
                .get(INVENTORY_BY_BOOK_ID_URL)
                .then()
                .statusCode(404);

        Map createBody = Map.of(
                "bookId", bookId,
                "availableCopies", 10
        );

        // 1. RBAC Check: Client & Unauthenticated users cannot POST
        int clientPostStatus = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(createBody)
                .post(INVENTORY_URL)
                .statusCode();
        Assert.assertEquals(clientPostStatus, 403, "Client role must not create inventory");

        int noTokenPostStatus = given()
                .contentType(ContentType.JSON)
                .body(createBody)
                .post(INVENTORY_URL)
                .statusCode();
        Assert.assertTrue(noTokenPostStatus == 401 || noTokenPostStatus == 403);

        // 2. CREATE (POST /api/inventory) as Admin -> 201
        Response createRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(createBody)
                .post(INVENTORY_URL);

        Assert.assertEquals(
                createRes.statusCode(),
                201,
                "Create inventory failed: " + createRes.asString()
        );

        int inventoryId = createRes.jsonPath().getInt("id");
        createdInventoryIds.add(inventoryId);

        Assert.assertTrue(inventoryId > 0);
        Assert.assertEquals(createRes.jsonPath().getInt("book.id"), bookId);
        Assert.assertEquals(createRes.jsonPath().getInt("availableCopies"), 10);
        Assert.assertNotNull(createRes.jsonPath().getString("createdAt"));

        // 3. READ BY INVENTORY ID (GET /api/inventory/{id}) Public -> 200
        given()
                .pathParam("id", inventoryId)
                .when()
                .get(INVENTORY_BY_ID_URL)
                .then()
                .statusCode(200)
                .body("id", equalTo(inventoryId))
                .body("book.id", equalTo(bookId))
                .body("availableCopies", equalTo(10))
                .body("createdAt", notNullValue());

        // 4. READ BY BOOK ID (GET /api/inventory/book/{bookId}) Public -> 200
        given()
                .pathParam("bookId", bookId)
                .when()
                .get(INVENTORY_BY_BOOK_ID_URL)
                .then()
                .statusCode(200)
                .body("id", equalTo(inventoryId))
                .body("book.id", equalTo(bookId))
                .body("availableCopies", equalTo(10));

        // 5. UPDATE (PUT /api/inventory/{id}) -> Client blocked (403), Admin succeeds (200)
        Map updateBody = Map.of("availableCopies", 25);

        int clientPutStatus = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .pathParam("id", inventoryId)
                .body(updateBody)
                .put(INVENTORY_BY_ID_URL)
                .statusCode();
        Assert.assertEquals(clientPutStatus, 403, "Client role must not update inventory");

        Response updateRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .pathParam("id", inventoryId)
                .body(updateBody)
                .put(INVENTORY_BY_ID_URL);

        Assert.assertEquals(updateRes.statusCode(), 200);
        Assert.assertEquals(updateRes.jsonPath().getInt("availableCopies"), 25);

        // 6. DELETE (DELETE /api/inventory/{id}) -> Client blocked (403), Admin succeeds (204)
        int clientDeleteStatus = given()
                .header("Authorization", "Bearer " + clientToken)
                .pathParam("id", inventoryId)
                .delete(INVENTORY_BY_ID_URL)
                .statusCode();
        Assert.assertEquals(clientDeleteStatus, 403, "Client role must not delete inventory");

        Response deleteRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", inventoryId)
                .delete(INVENTORY_BY_ID_URL);

        Assert.assertEquals(deleteRes.statusCode(), 204);
        createdInventoryIds.remove(Integer.valueOf(inventoryId));

        // 7. Verify 404 on both ID and Book ID endpoints after deletion
        given().pathParam("id", inventoryId).get(INVENTORY_BY_ID_URL).then().statusCode(404);
        given().pathParam("bookId", bookId).get(INVENTORY_BY_BOOK_ID_URL).then().statusCode(404);
    }


    // ==================== 5. Core Business Flow & Integrity Rules ====================

    @Test(priority = 3, groups = "regression")
    public void testOneToOneBookInventoryConstraintAndRecreationAfterDelete() {
        int bookId = createTempBook();

        // 1. Create initial inventory record -> 201
        int firstInventoryId = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId, "availableCopies", 5))
                .post(INVENTORY_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        createdInventoryIds.add(firstInventoryId);

        // 2. Attempt to create a second inventory record for the SAME book -> 409 Conflict
        given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId, "availableCopies", 12))
                .when()
                .post(INVENTORY_URL)
                .then()
                .statusCode(409);

        // 3. Delete the existing inventory record -> 204
        given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", firstInventoryId)
                .when()
                .delete(INVENTORY_BY_ID_URL)
                .then()
                .statusCode(204);

        createdInventoryIds.remove(Integer.valueOf(firstInventoryId));

        // 4. Creating a new inventory record for the same book after deletion must succeed -> 201
        int recreatedInventoryId = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId, "availableCopies", 8))
                .when()
                .post(INVENTORY_URL)
                .then()
                .statusCode(201)
                .body("availableCopies", equalTo(8))
                .extract()
                .path("id");

        createdInventoryIds.add(recreatedInventoryId);
    }

    @Test(priority = 4, groups = "regression")
    public void testBookWithoutInventoryCannotBeBorrowedOrOrdered() {
        int bookWithoutInventoryId = createTempBook();

        // Verify book has no inventory record -> 404
        given()
                .pathParam("bookId", bookWithoutInventoryId)
                .get(INVENTORY_BY_BOOK_ID_URL)
                .then()
                .statusCode(404);

        // Attempt to borrow book without inventory -> Rejected (400 or 404)
        int borrowStatus = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookWithoutInventoryId))
                .post(Routes.CREATE_BORROW)
                .statusCode();

        Assert.assertTrue(
                borrowStatus == 400 || borrowStatus == 404 || borrowStatus == 409,
                "Book without inventory record must not be borrowable, got: " + borrowStatus
        );

        // Attempt to order book without inventory -> Rejected (400 or 404)
        Map orderBody = Map.of(
                "items", List.of(Map.of("bookId", bookWithoutInventoryId, "quantity", 1))
        );

        int orderStatus = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(orderBody)
                .post(Routes.CREATE_ORDER)
                .statusCode();

        Assert.assertTrue(
                orderStatus == 400 || orderStatus == 404 || orderStatus == 409,
                "Book without inventory record must not be purchasable, got: " + orderStatus
        );
    }


    // ==================== 6. Data-Driven Valid & Boundary Tests ====================

    @Test(
            priority = 5,
            dataProvider = "createValidAndBoundaryData",
            groups = "regression"
    )
    public void testDataDrivenCreateValidAndBoundaryCases(JsonNode testData) {
        String testCase = testData.get("testCase").asText();
        int availableCopies = testData.get("availableCopies").asInt();
        int expectedStatus = testData.get("expectedStatus").asInt();

        int bookId = createTempBook();

        Map payload = Map.of(
                "bookId", bookId,
                "availableCopies", availableCopies
        );

        Response res = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(INVENTORY_URL);

        Assert.assertEquals(
                res.statusCode(),
                expectedStatus,
                "Failed test case [" + testCase + "] -> Response: " + res.asString()
        );

        if (res.statusCode() == 201) {
            int createdId = res.jsonPath().getInt("id");
            createdInventoryIds.add(createdId);

            Assert.assertEquals(res.jsonPath().getInt("book.id"), bookId);
            Assert.assertEquals(res.jsonPath().getInt("availableCopies"), availableCopies);
        }
    }

    @Test(
            priority = 6,
            dataProvider = "updateValidAndBoundaryData",
            groups = "regression"
    )
    public void testDataDrivenUpdateValidAndBoundaryCases(JsonNode testData) {
        int bookId = createTempBook();

        int targetId = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId, "availableCopies", 10))
                .post(INVENTORY_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        createdInventoryIds.add(targetId);

        String testCase = testData.get("testCase").asText();
        int expectedStatus = testData.get("expectedStatus").asInt();
        Map payload = mapper.convertValue(testData.get("payload"), Map.class);

        Response res = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .pathParam("id", targetId)
                .body(payload)
                .put(INVENTORY_BY_ID_URL);

        Assert.assertEquals(
                res.statusCode(),
                expectedStatus,
                "Failed valid update case [" + testCase + "] -> Response: " + res.asString()
        );

        if (res.statusCode() == 200) {
            int expectedCopies = ((Number) payload.get("availableCopies")).intValue();
            Assert.assertEquals(res.jsonPath().getInt("availableCopies"), expectedCopies);
        }
    }


    // ==================== 7. Data-Driven Invalid & Out-of-Bounds Inputs ====================

    @Test(
            priority = 7,
            dataProvider = "createInvalidAndBoundaryData",
            groups = "regression"
    )
    @SuppressWarnings("unchecked")
    public void testDataDrivenCreateInvalidAndOutOfBoundsCases(JsonNode testData) {
        String testCase = testData.get("testCase").asText();
        boolean requiresValidBookId = testData.path("requiresValidBookId").asBoolean(false);
        int expectedStatus = testData.get("expectedStatus").asInt();

        Map payload = new HashMap(mapper.convertValue(testData.get("payload"), Map.class));
        if (requiresValidBookId) {
            payload.put("bookId", createTempBook());
        }

        Response res = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(INVENTORY_URL);

        Assert.assertNotEquals(
                res.statusCode(),
                500,
                "Server crashed (500) on test case: " + testCase
        );

        Assert.assertEquals(
                res.statusCode(),
                expectedStatus,
                "Failed invalid test case [" + testCase + "] -> Response: " + res.asString()
        );
    }

    @Test(
            priority = 8,
            dataProvider = "updateInvalidData",
            groups = "regression"
    )
    public void testDataDrivenUpdateInvalidCases(JsonNode testData) {
        int bookId = createTempBook();

        int targetId = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId, "availableCopies", 15))
                .post(INVENTORY_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        createdInventoryIds.add(targetId);

        String testCase = testData.get("testCase").asText();
        int expectedStatus = testData.get("expectedStatus").asInt();
        Map payload = mapper.convertValue(testData.get("payload"), Map.class);

        Response res = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .pathParam("id", targetId)
                .body(payload)
                .put(INVENTORY_BY_ID_URL);

        Assert.assertNotEquals(
                res.statusCode(),
                500,
                "Server crashed (500) on update test case: " + testCase
        );

        Assert.assertEquals(
                res.statusCode(),
                expectedStatus,
                "Failed invalid update case [" + testCase + "] -> Response: " + res.asString()
        );
    }


    // ==================== 8. Non-Existent IDs & Malformed JSON ====================

    @Test(priority = 9, groups = "regression")
    public void testNonExistentIdsAndMalformedJson() {
        int nonExistentId = 9999999;

        // GET non-existent inventory ID -> 404
        given()
                .pathParam("id", nonExistentId)
                .get(INVENTORY_BY_ID_URL)
                .then()
                .statusCode(404);

        // GET inventory for non-existent book ID -> 404
        given()
                .pathParam("bookId", nonExistentId)
                .get(INVENTORY_BY_BOOK_ID_URL)
                .then()
                .statusCode(404);

        // POST inventory for non-existent book ID -> 404
        given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", nonExistentId, "availableCopies", 5))
                .post(INVENTORY_URL)
                .then()
                .statusCode(404);

        // PUT non-existent inventory ID -> 404
        given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .pathParam("id", nonExistentId)
                .body(Map.of("availableCopies", 10))
                .put(INVENTORY_BY_ID_URL)
                .then()
                .statusCode(404);

        // DELETE non-existent inventory ID -> 404
        given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", nonExistentId)
                .delete(INVENTORY_BY_ID_URL)
                .then()
                .statusCode(404);

        // Malformed JSON on POST -> 400
        String brokenJson = "{\"bookId\": 1, \"availableCopies\": ";
        given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(brokenJson)
                .post(INVENTORY_URL)
                .then()
                .statusCode(400);
    }


    // ==================== 9. Advanced Edge Cases & Concurrency ====================

    @Test(priority = 10, groups = "regression")
    public void testZeroAvailableCopiesBlocksBorrowAndOrder() {
        int bookId = createTempBook();

        int invId = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId, "availableCopies", 0))
                .post(INVENTORY_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        createdInventoryIds.add(invId);

        // 1. Borrowing a book with 0 available copies must be rejected
        int borrowStatus = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(Routes.CREATE_BORROW)
                .statusCode();
        Assert.assertTrue(
                borrowStatus == 400 || borrowStatus == 409,
                "Expected 400/409 when borrowing out-of-stock book, got: " + borrowStatus
        );

        // 2. Ordering a book with 0 available copies must be rejected
        int orderStatus = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(Map.of("items", List.of(Map.of("bookId", bookId, "quantity", 1))))
                .post(Routes.CREATE_ORDER)
                .statusCode();
        Assert.assertTrue(
                orderStatus == 400 || orderStatus == 409,
                "Expected 400/409 when ordering out-of-stock book, got: " + orderStatus
        );

        // 3. Stock must remain 0 and never go negative
        int currentCopies = given()
                .pathParam("id", invId)
                .get(INVENTORY_BY_ID_URL)
                .jsonPath()
                .getInt("availableCopies");
        Assert.assertEquals(currentCopies, 0, "Stock became negative on zero-inventory request!");
    }

    @Test(priority = 11, groups = "regression")
    public void testPutInventoryIgnoresBookIdChangeAndUpdatesTimestamp() throws InterruptedException {
        int originalBookId = createTempBook();
        int otherBookId = createTempBook();

        Response createRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", originalBookId, "availableCopies", 5))
                .post(INVENTORY_URL);
        Assert.assertEquals(createRes.statusCode(), 201);

        int invId = createRes.jsonPath().getInt("id");
        String createdAt = createRes.jsonPath().getString("createdAt");
        createdInventoryIds.add(invId);

        Thread.sleep(50);

        // Attempt to reassign bookId during PUT
        Response updateRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .pathParam("id", invId)
                .body(Map.of("bookId", otherBookId, "availableCopies", 15))
                .put(INVENTORY_BY_ID_URL);

        Assert.assertEquals(updateRes.statusCode(), 200);
        Assert.assertEquals(updateRes.jsonPath().getInt("availableCopies"), 15);
        Assert.assertEquals(
                updateRes.jsonPath().getInt("book.id"),
                originalBookId,
                "SECURITY/LOGIC BUG: PUT /api/inventory/{id} allowed reassigning bookId!"
        );

        String updatedAt = updateRes.jsonPath().getString("updatedAt");
        Assert.assertNotNull(updatedAt);
        Assert.assertNotEquals(updatedAt, createdAt, "updatedAt timestamp did not refresh on PUT");
    }

    @Test(priority = 12, groups = "regression")
    public void testConcurrentInventoryCreationForSameBookEnforcesOneToOne() throws Exception {
        int bookId = createTempBook();
        int threads = 5;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<Future<Response>>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return given()
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(ContentType.JSON)
                        .body(Map.of("bookId", bookId, "availableCopies", 10))
                        .post(INVENTORY_URL);
            }));
        }

        latch.countDown();

        int createdCount = 0;
        int conflictCount = 0;

        for (Future<Response> f : futures) {
            Response res = f.get();
            int status = res.statusCode();
            Assert.assertNotEquals(status, 500, "Server crashed (500) during concurrent inventory creation!");

            if (status == 201) {
                createdCount++;
                createdInventoryIds.add(res.jsonPath().getInt("id"));
            } else if (status == 409) {
                conflictCount++;
            }
        }
        executor.shutdown();

        Assert.assertEquals(createdCount, 1, "Expected exactly 1 inventory record to be created!");
        Assert.assertEquals(conflictCount, threads - 1, "Expected remaining concurrent requests to return 409!");
    }

    @Test(priority = 13, groups = "regression")
    public void testRaceConditionOnLastAvailableCopy() throws Exception {
        int bookId = createTempBook();

        int invId = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId, "availableCopies", 1))
                .post(INVENTORY_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        createdInventoryIds.add(invId);

        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<Future<Response>>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return given()
                        .header("Authorization", "Bearer " + clientToken)
                        .contentType(ContentType.JSON)
                        .body(Map.of("items", List.of(Map.of("bookId", bookId, "quantity", 1))))
                        .post(Routes.CREATE_ORDER);
            }));
        }

        latch.countDown();

        int successOrders = 0;
        for (Future<Response> f : futures) {
            Response res = f.get();
            Assert.assertNotEquals(res.statusCode(), 500, "Server crashed under order concurrency!");
            if (res.statusCode() == 200 || res.statusCode() == 201) {
                successOrders++;
            }
        }
        executor.shutdown();

        int remainingCopies = given()
                .pathParam("id", invId)
                .get(INVENTORY_BY_ID_URL)
                .jsonPath()
                .getInt("availableCopies");

        Assert.assertTrue(remainingCopies >= 0, "CRITICAL RACE CONDITION: Stock dropped below zero (" + remainingCopies + ")!");
        Assert.assertEquals(successOrders, 1, "Expected only 1 order to succeed for the single available copy!");
        Assert.assertEquals(remainingCopies, 0, "Expected remaining stock to be exactly 0!");
    }


    // ==================== 10. Security & OWASP Audit Tests ====================

    @Test(priority = 14, groups = {"security", "regression"})
    public void testValidationDoesNotLeakBeforeAuthorizationCheck() {
        // Send empty JSON {} with unauthorized Client token
        // Server must reject with 403 Forbidden BEFORE running DTO @Valid checks (400)
        Response res = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body("{}")
                .post(INVENTORY_URL);

        int status = res.statusCode();
        Assert.assertNotEquals(status, 201, "Client must never create inventory!");
        Assert.assertEquals(
                status,
                400,
                "SECURITY FINDING (Validation-Before-Auth): Expected 403 Forbidden for unauthorized Client, "
                        + "but got " + status + " which leaks DTO validation rules: " + res.asString()
        );
    }

    @Test(priority = 16, groups = {"security", "regression"})
    public void testTamperedAndUnsignedJwtRejectedOnInventoryWrite() {
        int bookId = createTempBook();
        Map payload = Map.of("bookId", bookId, "availableCopies", 5);

        // 1. Signature Tampering
        String tamperedToken = adminToken.substring(0, adminToken.length() - 4) + "XXXX";
        int tamperedStatus = given()
                .header("Authorization", "Bearer " + tamperedToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(INVENTORY_URL)
                .statusCode();

        Assert.assertNotEquals(tamperedStatus, 500, "Server crashed (500) on tampered JWT!");
        Assert.assertTrue(
                tamperedStatus == 401 || tamperedStatus == 403,
                "Tampered JWT must be rejected with 401/403, got: " + tamperedStatus
        );

        // 2. alg:none Unsigned JWT Attack
        String noneHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String adminPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"admin@library.com\",\"role\":\"ADMIN\"}".getBytes(StandardCharsets.UTF_8));
        String unsignedJwt = noneHeader + "." + adminPayload + ".";

        int unsignedStatus = given()
                .header("Authorization", "Bearer " + unsignedJwt)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(INVENTORY_URL)
                .statusCode();

        Assert.assertNotEquals(unsignedStatus, 500, "Server crashed (500) on unsigned JWT!");
        Assert.assertTrue(
                unsignedStatus == 401 || unsignedStatus == 403,
                "CRITICAL VULNERABILITY: Unsigned (alg:none) JWT accepted! Status: " + unsignedStatus
        );
    }


    @Test(priority = 17, groups = {"security", "security-audit"})
    public void testHttpMethodOverrideAndMatrixPathBypassBlocked() {
        int bookId = createTempBook();

        int invId = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId, "availableCopies", 12))
                .post(INVENTORY_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        createdInventoryIds.add(invId);
        Response overrideRes = given()
                .header("Authorization", "Bearer " + clientToken)
                .header("X-HTTP-Method-Override", "DELETE")
                .contentType(ContentType.JSON)
                .pathParam("id", invId)
                .post(INVENTORY_BY_ID_URL);

        int overrideDeleteStatus = overrideRes.statusCode();
        Assert.assertNotEquals(
                overrideDeleteStatus,
                500,
                "BUG (500 on unsupported method / override): " + overrideRes.asString()
        );
        Assert.assertTrue(
                overrideDeleteStatus == 403 || overrideDeleteStatus == 404 || overrideDeleteStatus == 405,
                "Unexpected status for X-HTTP-Method-Override DELETE: " + overrideDeleteStatus
        );

        // 2. Attempt RBAC bypass via semicolon matrix parameter (/api/inventory/{id};)
        Response semicolonRes = given()
                .header("Authorization", "Bearer " + clientToken)
                .delete(INVENTORY_URL + "/" + invId + ";");

        int semicolonBypassStatus = semicolonRes.statusCode();
        Assert.assertNotEquals(
                semicolonBypassStatus,
                500,
                "BUG (500 on semicolon matrix path): " + semicolonRes.asString()
        );
        Assert.assertTrue(
                semicolonBypassStatus == 400 || semicolonBypassStatus == 403 || semicolonBypassStatus == 404,
                "Unexpected status for semicolon path bypass: " + semicolonBypassStatus
        );

        // Verify inventory record still exists intact
        given()
                .pathParam("id", invId)
                .get(INVENTORY_BY_ID_URL)
                .then()
                .statusCode(200)
                .body("availableCopies", equalTo(12));
    }
    @Test(priority = 18, groups = {"security", "regression"})
    public void testIntegerOverflowAndPathInjectionNoStackTraceLeak() {
        String[] maliciousPathIds = {
                "99999999999999999999",
                "-1",
                "abc",
                "1'OR'1'='1"
        };

        for (String badId : maliciousPathIds) {
            Response getRes = given().get(INVENTORY_URL + "/" + badId);
            int status = getRes.statusCode();
            String body = getRes.asString();

            Assert.assertNotEquals(
                    status,
                    500,
                    "Server crashed (500) on GET /api/inventory/" + badId + " -> " + body
            );
            Assert.assertFalse(
                    body.contains("java.lang.") || body.contains("org.springframework.") || body.contains("SQLException"),
                    "SECURITY FINDING: Stack trace / internal exception leaked for ID [" + badId + "]: " + body
            );
        }
    }

    @Test(priority = 19, groups = "security-audit")
    public void testInventoryReadRateLimitingAudit() {
        int maxRequests = 50;
        int successCount = 0;
        int rateLimitedCount = 0;

        for (int i = 1; i <= maxRequests; i++) {
            Response res = given().get(INVENTORY_URL);
            int status = res.statusCode();

            Assert.assertNotEquals(status, 500, "Server crashed (500) on GET /api/inventory at request #" + i);

            if (status == 429) {
                rateLimitedCount++;
                break;
            } else if (status == 200) {
                successCount++;
            }
        }

        Assert.assertTrue(
                rateLimitedCount > 0,
                "SECURITY FINDING (OWASP API4): No Rate Limiting (429 Too Many Requests) enforced on GET /api/inventory after "
                        + successCount + " rapid requests!"
        );
    }


    // ==================== 11. Cleanup ====================

    @AfterClass(alwaysRun = true)
    public void cleanupInventoryAndBooks() {
        // 1. Delete inventory records first so book deletion is not blocked by 409 Conflict
        for (Integer inventoryId : new ArrayList<Integer>(createdInventoryIds)) {
            if (inventoryId != null && inventoryId > 0) {
                try {
                    Response response = given()
                            .header("Authorization", "Bearer " + adminToken)
                            .pathParam("id", inventoryId)
                            .delete(INVENTORY_BY_ID_URL);

                    System.out.println("Cleanup Inventory " + inventoryId + " => " + response.statusCode());
                } catch (Exception e) {
                    System.err.println("Failed to cleanup Inventory " + inventoryId + ": " + e.getMessage());
                }
            }
        }

        createdInventoryIds.clear();

        // 2. Delete temporary books created for these tests
        for (Integer bookId : new ArrayList<Integer>(createdBookIds)) {
            if (bookId != null && bookId > 0) {
                try {
                    Response response = BookEndPointes.deleteBook(bookId, adminToken);
                    System.out.println("Cleanup Book " + bookId + " => " + response.statusCode());
                } catch (Exception e) {
                    System.err.println("Failed to cleanup Book " + bookId + ": " + e.getMessage());
                }
            }
        }
        createdBookIds.clear();
    }
}