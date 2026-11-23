package api.test.Order;

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

public class OrdersTests extends BaseTest {
    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }

    private static final String ORDERS_URL =
            Routes.BASE_URL + "/api/orders";

    private static final String ORDER_BY_ID_URL =
            Routes.BASE_URL + "/api/orders/{id}";

    private static final String MY_ORDERS_URL =
            Routes.BASE_URL + "/api/orders/my";

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

    private int createTempBook(double price) {
        BookRequest bookPayload = new BookRequest(
                "Order Book " + randomLetters(6),
                "Order Author " + randomLetters(5),
                2025,
                "Commerce Press",
                "Fiction",
                null,
                price,
                "Temporary book created for order testing"
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

    private int[] createBookWithInventory(double price, int availableCopies) {
        int bookId = createTempBook(price);

        Response invRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId, "availableCopies", availableCopies))
                .post(INVENTORY_URL);

        Assert.assertEquals(
                invRes.statusCode(),
                201,
                "Failed to create inventory for book " + bookId + ": " + invRes.asString()
        );

        int invId = invRes.jsonPath().getInt("id");
        createdInventoryIds.add(invId);
        return new int[]{bookId, invId};
    }

    private int getAvailableCopiesByBookId(int bookId) {
        return given()
                .pathParam("bookId", bookId)
                .get(INVENTORY_BY_BOOK_ID_URL)
                .then()
                .statusCode(200)
                .extract()
                .path("availableCopies");
    }

    private String registerFreshClientToken() {
        String email = "buyer_" + randomLetters(8) + "@example.com";
        String password = "Password@123";
        String fullName = "Buyer " + randomLetters(6) + " " + randomLetters(6);

        Response regRes = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "fullName", fullName,
                        "email", email,
                        "password", password
                ))
                .post(Routes.BASE_URL + "/api/auth/register");

        Assert.assertTrue(
                regRes.statusCode() == 200 || regRes.statusCode() == 201,
                "Failed to register isolated client: " + regRes.asString()
        );

        String token = regRes.jsonPath().getString("token");
        if (token != null && !token.isEmpty()) {
            return token;
        }

        Response loginRes = given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", email, "password", password))
                .post(Routes.BASE_URL + "/api/auth/login");

        Assert.assertEquals(loginRes.statusCode(), 200, "Failed to login isolated client: " + loginRes.asString());
        token = loginRes.jsonPath().getString("token");
        if (token == null || token.isEmpty()) {
            token = loginRes.jsonPath().getString("accessToken");
        }
        Assert.assertNotNull(token, "Token was null after login");
        return token;
    }


    // ==================== 1. JSON Data Loader ====================

    private JsonNode loadTestData() {
        try {
            InputStream inputStream = getClass()
                    .getClassLoader()
                    .getResourceAsStream("testdata/ordersdata.json");

            if (inputStream == null) {
                inputStream = getClass()
                        .getClassLoader()
                        .getResourceAsStream("ordersdata.json");
            }

            if (inputStream != null) {
                try (InputStream is = inputStream) {
                    return mapper.readTree(is);
                }
            }

            File fallbackFile =
                    new File("src/test/resources/testdata/ordersdata.json");

            if (!fallbackFile.exists()) {
                fallbackFile =
                        new File("src/test/resources/ordersdata.json");
            }

            if (!fallbackFile.exists()) {
                throw new IOException(
                        "ordersdata.json was not found in test resources"
                );
            }

            return mapper.readTree(fallbackFile);

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to load ordersdata.json",
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

    @DataProvider(name = "createValidOrderData")
    public Object[][] createValidOrderData() {
        return extractCases("createValidCases");
    }

    @DataProvider(name = "createInvalidOrderData")
    public Object[][] createInvalidOrderData() {
        return extractCases("createInvalidCases");
    }


    // ==================== 3. Smoke & RBAC Access Tests ====================

    @Test(priority = 1, groups = {"smoke", "regression"})
    public void testSmokeEndpointsAndAdminOnlyAllOrdersRBAC() {
        // 1. Unauthenticated access rejected on all order endpoints
        int unauthMyStatus = given().get(MY_ORDERS_URL).statusCode();
        Assert.assertTrue(unauthMyStatus == 401 || unauthMyStatus == 403);

        int unauthAllStatus = given().get(ORDERS_URL).statusCode();
        Assert.assertTrue(unauthAllStatus == 401 || unauthAllStatus == 403);

        int unauthByIdStatus = given().pathParam("id", 1).get(ORDER_BY_ID_URL).statusCode();
        Assert.assertTrue(unauthByIdStatus == 401 || unauthByIdStatus == 403);

        int unauthPostStatus = given()
                .contentType(ContentType.JSON)
                .body(Map.of("items", List.of(Map.of("bookId", 1, "quantity", 1))))
                .post(ORDERS_URL)
                .statusCode();
        Assert.assertTrue(unauthPostStatus == 401 || unauthPostStatus == 403);

        // 2. Signed-in Client can read own order history -> 200
        given()
                .header("Authorization", "Bearer " + clientToken)
                .when()
                .get(MY_ORDERS_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", isA(List.class));

        // 3. Signed-in Client MUST NOT view all members' orders -> 403
        given()
                .header("Authorization", "Bearer " + clientToken)
                .when()
                .get(ORDERS_URL)
                .then()
                .statusCode(403);

        // 4. Admin CAN view all members' orders -> 200
        given()
                .header("Authorization", "Bearer " + adminToken)
                .when()
                .get(ORDERS_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", isA(List.class));
    }


    // ==================== 4. Full Multi-Item Checkout Lifecycle & Math ====================

    @Test(priority = 2, groups = {"smoke", "regression"})
    public void testMultiItemCheckoutCalculationStockSyncAndHistory() {
        String buyerToken = registerFreshClientToken();

        int book1Id = createBookWithInventory(120.0, 5)[0];
        int book2Id = createBookWithInventory(180.0, 4)[0];

        Map<String, Object> orderPayload = Map.of(
                "items", List.of(
                        Map.of("bookId", book1Id, "quantity", 2),
                        Map.of("bookId", book2Id, "quantity", 1)
                )
        );

        // 1. Place Multi-Item Order (POST /api/orders) -> 201
        Response createRes = given()
                .header("Authorization", "Bearer " + buyerToken)
                .contentType(ContentType.JSON)
                .body(orderPayload)
                .post(ORDERS_URL);

        Assert.assertEquals(
                createRes.statusCode(),
                201,
                "Multi-item checkout failed: " + createRes.asString()
        );

        int orderId = createRes.jsonPath().getInt("id");
        Assert.assertTrue(orderId > 0);
        Assert.assertEquals(createRes.jsonPath().getString("status"), "COMPLETED");
        Assert.assertNotNull(createRes.jsonPath().getString("createdAt"));

        // Verify server-side price math: (120 * 2) + (180 * 1) = 420.0
        Assert.assertEquals(createRes.jsonPath().getFloat("totalPrice"), 420.0f, 0.01f);
        List items = createRes.jsonPath().getList("items");
        Assert.assertEquals(items.size(), 2, "Expected 2 line items in created order");

        for (Object itemObj : items) {
            Map item = (Map) itemObj;
            Map bookObj = (Map) item.get("book");
            int itemBookId = ((Number) bookObj.get("id")).intValue();
            int qty = ((Number) item.get("quantity")).intValue();
            double unitPrice = ((Number) item.get("unitPrice")).doubleValue();
            double subtotal = ((Number) item.get("subtotal")).doubleValue();

            if (itemBookId == book1Id) {
                Assert.assertEquals(qty, 2);
                Assert.assertEquals(unitPrice, 120.0, 0.01);
                Assert.assertEquals(subtotal, 240.0, 0.01);
            } else if (itemBookId == book2Id) {
                Assert.assertEquals(qty, 1);
                Assert.assertEquals(unitPrice, 180.0, 0.01);
                Assert.assertEquals(subtotal, 180.0, 0.01);
            } else {
                Assert.fail("Unexpected book ID in order items: " + itemBookId);
            }
        }

        // Verify Inventory decremented accurately for BOTH books
        Assert.assertEquals(getAvailableCopiesByBookId(book1Id), 3, "Book 1 stock should drop from 5 to 3");
        Assert.assertEquals(getAvailableCopiesByBookId(book2Id), 3, "Book 2 stock should drop from 4 to 3");

        // 2. Get Single Order by ID (GET /api/orders/{id}) -> 200
        given()
                .header("Authorization", "Bearer " + buyerToken)
                .pathParam("id", orderId)
                .when()
                .get(ORDER_BY_ID_URL)
                .then()
                .statusCode(200)
                .body("id", equalTo(orderId))
                .body("status", equalTo("COMPLETED"))
                .body("totalPrice", equalTo(420.0f))
                .body("createdAt", notNullValue());

        // 3. Get My Order History (GET /api/orders/my) contains this order -> 200
        List<Integer> myOrderIds = given()
                .header("Authorization", "Bearer " + buyerToken)
                .when()
                .get(MY_ORDERS_URL)
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        Assert.assertTrue(myOrderIds.contains(orderId), "Created order missing from /api/orders/my");
    }


    // ==================== 5. All-or-Nothing Atomicity Business Rules ====================

    @Test(priority = 3, groups = "regression")
    public void testAllOrNothingRollbackWhenAnyCartItemHasInsufficientStock() {
        String buyerToken = registerFreshClientToken();

        // Book 1 has plenty of stock (6), Book 2 only has (1)
        int book1Id = createBookWithInventory(90.0, 6)[0];
        int book2Id = createBookWithInventory(110.0, 1)[0];

        // Cart requests 2 of Book 1 (valid) + 2 of Book 2 (exceeds stock of 1)
        Map<String, Object> orderPayload = Map.of(
                "items", List.of(
                        Map.of("bookId", book1Id, "quantity", 2),
                        Map.of("bookId", book2Id, "quantity", 2)
                )
        );

        Response res = given()
                .header("Authorization", "Bearer " + buyerToken)
                .contentType(ContentType.JSON)
                .body(orderPayload)
                .post(ORDERS_URL);

        Assert.assertEquals(
                res.statusCode(),
                400,
                "Expected 400 when second cart item has insufficient stock, got: " + res.asString()
        );

        // CRITICAL ALL-OR-NOTHING CHECK: Book 1's stock must NOT be deducted!
        Assert.assertEquals(
                getAvailableCopiesByBookId(book1Id),
                6,
                "ALL-OR-NOTHING ATOMICITY BUG: Book 1 stock was deducted even though the order failed!"
        );
        Assert.assertEquals(
                getAvailableCopiesByBookId(book2Id),
                1,
                "Book 2 stock changed on rejected order!"
        );
    }

    @Test(priority = 4, groups = "regression")
    public void testAllOrNothingRollbackWhenSecondItemBookOrInventoryMissing() {
        String buyerToken = registerFreshClientToken();

        int validBookId = createBookWithInventory(85.0, 5)[0];
        int bookWithoutInventoryId = createTempBook(60.0);

        // Case A: Valid book + Book without inventory record -> 404 (or 400) & no stock deducted
        Response missingInvRes = given()
                .header("Authorization", "Bearer " + buyerToken)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "items", List.of(
                                Map.of("bookId", validBookId, "quantity", 2),
                                Map.of("bookId", bookWithoutInventoryId, "quantity", 1)
                        )
                ))
                .post(ORDERS_URL);

        Assert.assertTrue(
                missingInvRes.statusCode() == 404 || missingInvRes.statusCode() == 400,
                "Expected 404/400 when item lacks inventory record, got: " + missingInvRes.statusCode()
        );
        Assert.assertEquals(
                getAvailableCopiesByBookId(validBookId),
                5,
                "ATOMICITY BUG: Valid book stock deducted when another item lacked inventory!"
        );

        // Case B: Valid book + Non-existent book ID -> 404 & no stock deducted
        Response nonExistentBookRes = given()
                .header("Authorization", "Bearer " + buyerToken)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "items", List.of(
                                Map.of("bookId", validBookId, "quantity", 2),
                                Map.of("bookId", 9999999, "quantity", 1)
                        )
                ))
                .post(ORDERS_URL);

        Assert.assertEquals(nonExistentBookRes.statusCode(), 404);
        Assert.assertEquals(
                getAvailableCopiesByBookId(validBookId),
                5,
                "ATOMICITY BUG: Valid book stock deducted when another item did not exist!"
        );
    }


    // ==================== 6. Bug-Hunting & Concurrency Traps ====================

    @Test(priority = 5, groups = {"regression", "security-audit"})
    public void testSplitDuplicateBookItemsInSameCartCannotBypassStock() {
        String buyerToken = registerFreshClientToken();

        // Only 1 copy available in stock
        int bookId = createBookWithInventory(100.0, 1)[0];

        // Bug Trap: Send the SAME bookId twice with quantity=1 each (total requested = 2 > stock 1)
        Map<String, Object> splitCartPayload = Map.of(
                "items", List.of(
                        Map.of("bookId", bookId, "quantity", 1),
                        Map.of("bookId", bookId, "quantity", 1)
                )
        );

        Response res = given()
                .header("Authorization", "Bearer " + buyerToken)
                .contentType(ContentType.JSON)
                .body(splitCartPayload)
                .post(ORDERS_URL);

        int remainingStock = getAvailableCopiesByBookId(bookId);

        Assert.assertNotEquals(res.statusCode(), 500, "Server crashed (500) on duplicate book lines in cart!");
        Assert.assertTrue(
                remainingStock >= 0,
                "CRITICAL SPLIT-ITEM STOCK BYPASS BUG: Stock dropped below zero (" + remainingStock + ")!"
        );
        Assert.assertEquals(
                res.statusCode(),
                400,
                "BUSINESS LOGIC BUG: Duplicate book lines in cart bypassed available stock check! Remaining stock: "
                        + remainingStock + " | Response: " + res.asString()
        );
        Assert.assertEquals(remainingStock, 1);
    }

    @Test(priority = 6, groups = {"regression", "security-audit"})
    public void testIntegerMaxQuantityOverflowHandledCleanlyWithout500() {
        String buyerToken = registerFreshClientToken();
        int bookId = createBookWithInventory(99999.99, 5)[0];

        Map<String, Object> overflowPayload = Map.of(
                "items", List.of(
                        Map.of("bookId", bookId, "quantity", Integer.MAX_VALUE)
                )
        );

        Response res = given()
                .header("Authorization", "Bearer " + buyerToken)
                .contentType(ContentType.JSON)
                .body(overflowPayload)
                .post(ORDERS_URL);

        Assert.assertNotEquals(
                res.statusCode(),
                500,
                "BUG (500 on Integer.MAX_VALUE quantity): " + res.asString()
        );
        Assert.assertEquals(res.statusCode(), 400);
        Assert.assertEquals(getAvailableCopiesByBookId(bookId), 5);
    }

    @Test(priority = 7, groups = {"regression", "security-audit"})
    public void testConcurrentOrdersOnLimitedStockNeverGoNegative() throws Exception {
        int initialStock = 2;
        int bookId = createBookWithInventory(100.0, initialStock)[0];
        int threads = 5;

        String[] buyerTokens = new String[threads];
        for (int i = 0; i < threads; i++) {
            buyerTokens[i] = registerFreshClientToken();
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<Future<Response>>();

        for (int i = 0; i < threads; i++) {
            final String token = buyerTokens[i];
            futures.add(executor.submit(() -> {
                latch.await();
                return given()
                        .header("Authorization", "Bearer " + token)
                        .contentType(ContentType.JSON)
                        .body(Map.of("items", List.of(Map.of("bookId", bookId, "quantity", 1))))
                        .post(ORDERS_URL);
            }));
        }

        latch.countDown();

        int successOrders = 0;
        for (Future<Response> f : futures) {
            Response res = f.get();
            Assert.assertNotEquals(res.statusCode(), 500, "Server crashed (500) under concurrent orders!");
            if (res.statusCode() == 201) {
                successOrders++;
            }
        }
        executor.shutdown();

        int remainingStock = getAvailableCopiesByBookId(bookId);
        Assert.assertTrue(
                remainingStock >= 0,
                "CRITICAL CONCURRENCY BUG: Inventory stock went negative (" + remainingStock + ")!"
        );
        Assert.assertEquals(
                successOrders,
                initialStock,
                "Expected exactly " + initialStock + " orders to succeed, but " + successOrders + " succeeded!"
        );
        Assert.assertEquals(remainingStock, 0);
    }


    // ==================== 7. Data-Driven Valid & Invalid Tests ====================

    @Test(
            priority = 8,
            dataProvider = "createValidOrderData",
            groups = "regression"
    )
    public void testDataDrivenCreateValidOrders(JsonNode testData) {
        String testCase = testData.get("testCase").asText();
        int initialStock = testData.get("initialStock").asInt();
        int orderQuantity = testData.get("orderQuantity").asInt();
        double bookPrice = testData.get("bookPrice").asDouble();
        int expectedStatus = testData.get("expectedStatus").asInt();

        String buyerToken = registerFreshClientToken();
        int bookId = createBookWithInventory(bookPrice, initialStock)[0];

        Map<String, Object> payload = Map.of(
                "items", List.of(Map.of("bookId", bookId, "quantity", orderQuantity))
        );

        Response res = given()
                .header("Authorization", "Bearer " + buyerToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(ORDERS_URL);

        Assert.assertEquals(
                res.statusCode(),
                expectedStatus,
                "Failed valid order case [" + testCase + "] -> Response: " + res.asString()
        );

        if (res.statusCode() == 201) {
            double expectedTotal = bookPrice * orderQuantity;
            Assert.assertEquals(res.jsonPath().getDouble("totalPrice"), expectedTotal, 0.01);
            Assert.assertEquals(res.jsonPath().getString("status"), "COMPLETED");
            Assert.assertEquals(getAvailableCopiesByBookId(bookId), initialStock - orderQuantity);
        }
    }

    @Test(
            priority = 9,
            dataProvider = "createInvalidOrderData",
            groups = "regression"
    )
    @SuppressWarnings("unchecked")
    public void testDataDrivenCreateInvalidOrders(JsonNode testData) {
        String testCase = testData.get("testCase").asText();
        boolean requiresValidBookId = testData.path("requiresValidBookId").asBoolean(false);
        int expectedStatus = testData.get("expectedStatus").asInt();

        Map<String, Object> payload;
        if (requiresValidBookId) {
            int bookId = createBookWithInventory(100.0, 5)[0];
            Map<String, Object> item = new HashMap<String, Object>();
            item.put("bookId", bookId);
            JsonNode qtyNode = testData.get("quantity");
            item.put("quantity", (qtyNode == null || qtyNode.isNull()) ? null : qtyNode.asInt());
            payload = Map.of("items", List.of(item));
        } else {
            payload = mapper.convertValue(testData.get("payload"), Map.class);
        }

        Response res = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(ORDERS_URL);

        Assert.assertNotEquals(
                res.statusCode(),
                500,
                "Server crashed (500) on invalid order case: " + testCase
        );

        Assert.assertEquals(
                res.statusCode(),
                expectedStatus,
                "Failed invalid order case [" + testCase + "] -> Response: " + res.asString()
        );
    }


    // ==================== 8. Security & OWASP Audit Tests ====================

    @Test(priority = 10, groups = {"security", "regression"})
    public void testBolaIdorOwnershipAndIsolationAndNoPasswordLeak() {
        String userAToken = registerFreshClientToken();
        String userBToken = registerFreshClientToken();
        int bookId = createBookWithInventory(130.0, 3)[0];

        Response createRes = given()
                .header("Authorization", "Bearer " + userAToken)
                .contentType(ContentType.JSON)
                .body(Map.of("items", List.of(Map.of("bookId", bookId, "quantity", 1))))
                .post(ORDERS_URL);
        Assert.assertEquals(createRes.statusCode(), 201);

        Assert.assertFalse(
                createRes.asString().contains("\"password\"") || createRes.asString().contains("passwordHash"),
                "SECURITY FINDING: Password field leaked in POST /api/orders response!"
        );

        int orderId = createRes.jsonPath().getInt("id");

        // 1. User B CANNOT view User A's order via GET /api/orders/{id} -> 403
        given()
                .header("Authorization", "Bearer " + userBToken)
                .pathParam("id", orderId)
                .when()
                .get(ORDER_BY_ID_URL)
                .then()
                .statusCode(403);

        // 2. User B's /api/orders/my MUST NOT contain User A's order
        List<Integer> userBOrders = given()
                .header("Authorization", "Bearer " + userBToken)
                .get(MY_ORDERS_URL)
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        Assert.assertFalse(
                userBOrders.contains(orderId),
                "CRITICAL BOLA BUG: User A's order appeared in User B's /api/orders/my!"
        );

        // 3. Admin CAN view User A's order by ID and in GET /api/orders without password leaks
        given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", orderId)
                .when()
                .get(ORDER_BY_ID_URL)
                .then()
                .statusCode(200)
                .body("id", equalTo(orderId));

        Response adminAllRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .get(ORDERS_URL);
        Assert.assertEquals(adminAllRes.statusCode(), 200);
        Assert.assertFalse(adminAllRes.asString().contains("\"password\""));
        List<Integer> allOrderIds = adminAllRes.jsonPath().getList("id");
        Assert.assertTrue(allOrderIds.contains(orderId));
    }

    @Test(priority = 11, groups = {"security", "regression"})
    public void testMassAssignmentPriceAndStatusTamperingIgnored() {
        String buyerToken = registerFreshClientToken();
        double realPrice = 200.0;
        int bookId = createBookWithInventory(realPrice, 3)[0];

        Map<String, Object> tamperedItem = new HashMap<String, Object>();
        tamperedItem.put("id", 777777);
        tamperedItem.put("bookId", bookId);
        tamperedItem.put("quantity", 2);
        tamperedItem.put("unitPrice", 0.01);
        tamperedItem.put("subtotal", 0.02);

        Map<String, Object> tamperedOrder = new HashMap<String, Object>();
        tamperedOrder.put("id", 999999);
        tamperedOrder.put("totalPrice", 0.02);
        tamperedOrder.put("status", "CANCELLED");
        tamperedOrder.put("createdAt", "2020-01-01T00:00:00");
        tamperedOrder.put("items", List.of(tamperedItem));

        Response res = given()
                .header("Authorization", "Bearer " + buyerToken)
                .contentType(ContentType.JSON)
                .body(tamperedOrder)
                .post(ORDERS_URL);

        Assert.assertEquals(res.statusCode(), 201, "Order failed: " + res.asString());

        Assert.assertNotEquals(res.jsonPath().getInt("id"), 999999, "MASS ASSIGNMENT BUG: Spoofed order ID accepted!");
        Assert.assertEquals(res.jsonPath().getString("status"), "COMPLETED", "MASS ASSIGNMENT BUG: Spoofed status accepted!");
        Assert.assertEquals(
                res.jsonPath().getDouble("totalPrice"),
                400.0,
                0.01,
                "CRITICAL PRICE TAMPERING BUG: Client bypassed server price calculation!"
        );
        Assert.assertEquals(res.jsonPath().getDouble("items[0].unitPrice"), realPrice, 0.01);
        Assert.assertEquals(res.jsonPath().getDouble("items[0].subtotal"), 400.0, 0.01);
    }

    @Test(priority = 12, groups = {"security", "regression"})
    public void testTamperedAndUnsignedJwtRejectedOnOrders() {
        int bookId = createBookWithInventory(100.0, 2)[0];
        Map<String, Object> payload = Map.of(
                "items", List.of(Map.of("bookId", bookId, "quantity", 1))
        );

        // 1. Tampered JWT signature
        String tamperedToken = clientToken.substring(0, clientToken.length() - 4) + "ZZZZ";
        int tamperedStatus = given()
                .header("Authorization", "Bearer " + tamperedToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(ORDERS_URL)
                .statusCode();

        Assert.assertNotEquals(tamperedStatus, 500, "Server crashed (500) on tampered JWT!");
        Assert.assertTrue(tamperedStatus == 401 || tamperedStatus == 403);

        // 2. Unsigned (alg:none) JWT
        String noneHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String spoofPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"admin@library.com\",\"role\":\"ADMIN\"}".getBytes(StandardCharsets.UTF_8));
        String unsignedJwt = noneHeader + "." + spoofPayload + ".";

        int unsignedStatus = given()
                .header("Authorization", "Bearer " + unsignedJwt)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(ORDERS_URL)
                .statusCode();

        Assert.assertNotEquals(unsignedStatus, 500, "Server crashed (500) on unsigned JWT!");
        Assert.assertTrue(unsignedStatus == 401 || unsignedStatus == 403);
    }

    @Test(priority = 13, groups = {"security", "security-audit"})
    public void testNonExistentIdsOverflowUnsupportedMethodsAndMalformedJson() {
        int nonExistentId = 9999999;

        // 1. Non-existent order ID -> 404
        given()
                .header("Authorization", "Bearer " + clientToken)
                .pathParam("id", nonExistentId)
                .get(ORDER_BY_ID_URL)
                .then()
                .statusCode(404);

        // 2. Malformed JSON -> 400
        String brokenJson = "{\"items\": [{\"bookId\": 1, \"quantity\": ";
        given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(brokenJson)
                .post(ORDERS_URL)
                .then()
                .statusCode(400);

        // 3. Unsupported HTTP methods on /api/orders/{id} (must return 405, never 500)
        Response deleteRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", 1)
                .delete(ORDER_BY_ID_URL);
        Assert.assertNotEquals(deleteRes.statusCode(), 500, "BUG (500 on DELETE /orders/{id}): " + deleteRes.asString());

        Response putRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .pathParam("id", 1)
                .body("{}")
                .put(ORDER_BY_ID_URL);
        Assert.assertNotEquals(putRes.statusCode(), 500, "BUG (500 on PUT /orders/{id}): " + putRes.asString());

        // 4. Path Injection & Long Overflow -> Must not return 500 or stack trace
        String[] maliciousIds = {"99999999999999999999", "-1", "abc", "1'OR'1'='1"};
        for (String badId : maliciousIds) {
            Response res = given()
                    .header("Authorization", "Bearer " + clientToken)
                    .get(ORDERS_URL + "/" + badId);

            String body = res.asString();
            Assert.assertNotEquals(
                    res.statusCode(),
                    500,
                    "BUG (500) on GET /api/orders/" + badId + " -> " + body
            );
            Assert.assertFalse(
                    body.contains("java.lang.") || body.contains("org.springframework."),
                    "SECURITY FINDING: Stack trace leaked for order ID [" + badId + "]: " + body
            );
        }
    }

    @Test(priority = 14, groups = "security-audit")
    public void testMyOrdersRateLimitingAudit() {
        int maxRequests = 50;
        int successCount = 0;
        int rateLimitedCount = 0;

        for (int i = 1; i <= maxRequests; i++) {
            Response res = given()
                    .header("Authorization", "Bearer " + clientToken)
                    .get(MY_ORDERS_URL);

            int status = res.statusCode();
            Assert.assertNotEquals(status, 500, "Server crashed (500) on GET /api/orders/my at request #" + i);

            if (status == 429) {
                rateLimitedCount++;
                break;
            } else if (status == 200) {
                successCount++;
            }
        }

        Assert.assertTrue(
                rateLimitedCount > 0,
                "SECURITY FINDING (OWASP API4): No Rate Limiting (429) enforced on GET /api/orders/my after "
                        + successCount + " rapid requests!"
        );
    }
    // ==================== 9. Additional Bug-Hunting & Edge Cases ====================

    @Test(priority = 15, groups = {"regression", "security-audit"})
    public void testNullElementInsideItemsArrayDoesNotCause500() {
        List<Object> itemsWithNull = new ArrayList<Object>();
        itemsWithNull.add(null);

        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("items", itemsWithNull);

        Response res = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(ORDERS_URL);

        Assert.assertNotEquals(
                res.statusCode(),
                500,
                "BUG (500 NullPointerException): Sending {\"items\": [null]} crashed the server! -> " + res.asString()
        );
        Assert.assertEquals(res.statusCode(), 400);
    }

    @Test(priority = 16, groups = {"regression", "security-audit"})
    public void testDuplicateBookLinesWithSufficientStockDeductsFullQuantityAndMath() {
        String buyerToken = registerFreshClientToken();
        double price = 100.0;
        int initialStock = 6;
        int bookId = createBookWithInventory(price, initialStock)[0];

        // Send the SAME bookId twice: qty 1 + qty 2 = 3 total copies
        Map<String, Object> payload = Map.of(
                "items", List.of(
                        Map.of("bookId", bookId, "quantity", 1),
                        Map.of("bookId", bookId, "quantity", 2)
                )
        );

        Response res = given()
                .header("Authorization", "Bearer " + buyerToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(ORDERS_URL);

        Assert.assertNotEquals(res.statusCode(), 500, "Server crashed (500) on duplicate book lines!");

        int remainingStock = getAvailableCopiesByBookId(bookId);

        if (res.statusCode() == 201) {
            // If accepted, totalPrice MUST be 300.0 and stock MUST drop by 3 (from 6 to 3)
            Assert.assertEquals(
                    res.jsonPath().getDouble("totalPrice"),
                    300.0,
                    0.01,
                    "MATH BUG: Duplicate book lines in cart miscalculated totalPrice!"
            );
            Assert.assertEquals(
                    remainingStock,
                    3,
                    "LOST UPDATE STOCK BUG: Ordered 1+2=3 copies, expected stock 3, but found: " + remainingStock
            );
        } else {
            // If duplicate book lines are explicitly disallowed, it must return 400 and deduct 0 stock
            Assert.assertEquals(res.statusCode(), 400);
            Assert.assertEquals(remainingStock, initialStock);
        }
    }

    @Test(priority = 17, groups = "regression")
    public void testZeroStockBookCheckoutRejectedWith400() {
        String buyerToken = registerFreshClientToken();
        int bookId = createBookWithInventory(95.0, 0)[0];

        Response res = given()
                .header("Authorization", "Bearer " + buyerToken)
                .contentType(ContentType.JSON)
                .body(Map.of("items", List.of(Map.of("bookId", bookId, "quantity", 1))))
                .post(ORDERS_URL);

        Assert.assertEquals(
                res.statusCode(),
                400,
                "Expected 400 when ordering out-of-stock (0 copies) book, got: " + res.asString()
        );
        Assert.assertEquals(getAvailableCopiesByBookId(bookId), 0);
    }

    @Test(priority = 18, groups = "regression")
    public void testDeleteBookBlockedAfterOrderExists() {
        String buyerToken = registerFreshClientToken();
        int bookId = createBookWithInventory(110.0, 3)[0];

        given()
                .header("Authorization", "Bearer " + buyerToken)
                .contentType(ContentType.JSON)
                .body(Map.of("items", List.of(Map.of("bookId", bookId, "quantity", 1))))
                .post(ORDERS_URL)
                .then()
                .statusCode(201);

        Response deleteBookRes = BookEndPointes.deleteBook(bookId, adminToken);
        Assert.assertNotEquals(
                deleteBookRes.statusCode(),
                500,
                "Server crashed (500) when deleting a book referenced by an order!"
        );
        Assert.assertTrue(
                deleteBookRes.statusCode() == 409 || deleteBookRes.statusCode() == 400,
                "Expected 409/400 when deleting a book with existing order history, got: " + deleteBookRes.statusCode()
        );
    }

    @Test(priority = 19, groups = "regression")
    public void testNegativeAndZeroBookIdInsideOrderItemsHandledCleanly() {
        int[] badBookIds = {0, -1};

        for (int badBookId : badBookIds) {
            Response res = given()
                    .header("Authorization", "Bearer " + clientToken)
                    .contentType(ContentType.JSON)
                    .body(Map.of("items", List.of(Map.of("bookId", badBookId, "quantity", 1))))
                    .post(ORDERS_URL);

            Assert.assertNotEquals(
                    res.statusCode(),
                    500,
                    "Server crashed (500) for bookId=" + badBookId + " in order item!"
            );
            Assert.assertTrue(
                    res.statusCode() == 400 || res.statusCode() == 404,
                    "Expected 400 or 404 for bookId=" + badBookId + ", got: " + res.statusCode()
            );
        }
    }

    @Test(priority = 20, groups = "regression")
    public void testAdminUserCanPlaceOrderAndViewOwnOrderHistory() {
        int bookId = createBookWithInventory(65.0, 2)[0];

        Response createRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("items", List.of(Map.of("bookId", bookId, "quantity", 1))))
                .post(ORDERS_URL);

        Assert.assertEquals(createRes.statusCode(), 201, "Admin self-checkout failed: " + createRes.asString());
        int orderId = createRes.jsonPath().getInt("id");

        List<Integer> adminMyOrders = given()
                .header("Authorization", "Bearer " + adminToken)
                .get(MY_ORDERS_URL)
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        Assert.assertTrue(adminMyOrders.contains(orderId), "Admin's own order missing from /api/orders/my");
    }


    // ==================== 9. Cleanup ====================

    @AfterClass(alwaysRun = true)
    public void cleanupInventoryAndBooks() {
        // 1. Delete inventory records first
        for (Integer invId : new ArrayList<Integer>(createdInventoryIds)) {
            if (invId != null && invId > 0) {
                try {
                    given()
                            .header("Authorization", "Bearer " + adminToken)
                            .pathParam("id", invId)
                            .delete(INVENTORY_BY_ID_URL);
                } catch (Exception ignored) {
                }
            }
        }
        createdInventoryIds.clear();

        // 2. Attempt to delete temporary books (books referenced by historical orders may return 409)
        for (Integer bookId : new ArrayList<Integer>(createdBookIds)) {
            if (bookId != null && bookId > 0) {
                try {
                    BookEndPointes.deleteBook(bookId, adminToken);
                } catch (Exception ignored) {
                }
            }
        }
        createdBookIds.clear();
    }
}