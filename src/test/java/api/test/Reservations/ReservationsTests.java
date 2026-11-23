package api.test.Reservations;

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

public class ReservationsTests extends BaseTest {
    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }

    private static final String RESERVATIONS_URL =
            Routes.BASE_URL + "/api/reservations";

    private static final String CANCEL_RESERVATION_URL =
            Routes.BASE_URL + "/api/reservations/{id}/cancel";

    private static final String MY_RESERVATIONS_URL =
            Routes.BASE_URL + "/api/reservations/my";

    private static final String BORROWS_URL =
            Routes.BASE_URL + "/api/borrows";

    private static final String RETURN_BORROW_URL =
            Routes.BASE_URL + "/api/borrows/{id}/return";

    private static final String INVENTORY_URL =
            Routes.BASE_URL + "/api/inventory";

    private static final String INVENTORY_BY_ID_URL =
            Routes.BASE_URL + "/api/inventory/{id}";

    private final ObjectMapper mapper = new ObjectMapper();

    private final List<Integer> createdInventoryIds =
            Collections.synchronizedList(new ArrayList<Integer>());

    private final List<Integer> createdBookIds =
            Collections.synchronizedList(new ArrayList<Integer>());

    // Tracks [reservationId, ownerToken] to cancel lingering WAITING reservations in @AfterClass
    private final List<String[]> waitingReservationsToCleanup =
            Collections.synchronizedList(new ArrayList<String[]>());

    // Tracks [borrowId, borrowerToken] to return active borrows in @AfterClass
    private final List<String[]> activeBorrowsToCleanup =
            Collections.synchronizedList(new ArrayList<String[]>());


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
                "Reserve Book " + randomLetters(6),
                "Reserve Author " + randomLetters(5),
                2025,
                "Waitlist Press",
                "Fiction",
                null,
                90.0,
                "Temporary book created for reservation testing"
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

    private int[] createBookWithInventory(int availableCopies) {
        int bookId = createTempBook();

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

    private String registerFreshClientToken() {
        String email = "reserver_" + randomLetters(8) + "@example.com";
        String password = "Password@123";
        String fullName = "Reserver " + randomLetters(6) + " " + randomLetters(6);

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
                    .getResourceAsStream("testdata/reservationsdata.json");

            if (inputStream == null) {
                inputStream = getClass()
                        .getClassLoader()
                        .getResourceAsStream("reservationsdata.json");
            }

            if (inputStream != null) {
                try (InputStream is = inputStream) {
                    return mapper.readTree(is);
                }
            }

            File fallbackFile =
                    new File("src/test/resources/testdata/reservationsdata.json");

            if (!fallbackFile.exists()) {
                fallbackFile =
                        new File("src/test/resources/reservationsdata.json");
            }

            if (!fallbackFile.exists()) {
                throw new IOException(
                        "reservationsdata.json was not found in test resources"
                );
            }

            return mapper.readTree(fallbackFile);

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to load reservationsdata.json",
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

    @DataProvider(name = "createValidReservationData")
    public Object[][] createValidReservationData() {
        return extractCases("createValidCases");
    }

    @DataProvider(name = "createInvalidReservationData")
    public Object[][] createInvalidReservationData() {
        return extractCases("createInvalidCases");
    }


    // ==================== 3. Smoke & RBAC Access Tests ====================

    @Test(priority = 1, groups = {"smoke", "regression"})
    public void testSmokeEndpointsAndAdminOnlyAllReservationsRBAC() {
        // 1. Unauthenticated access rejected on all reservation endpoints
        int unauthMyStatus = given().get(MY_RESERVATIONS_URL).statusCode();
        Assert.assertTrue(unauthMyStatus == 401 || unauthMyStatus == 403);

        int unauthAllStatus = given().get(RESERVATIONS_URL).statusCode();
        Assert.assertTrue(unauthAllStatus == 401 || unauthAllStatus == 403);

        int unauthPostStatus = given()
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", 1))
                .post(RESERVATIONS_URL)
                .statusCode();
        Assert.assertTrue(unauthPostStatus == 401 || unauthPostStatus == 403);

        int unauthCancelStatus = given()
                .pathParam("id", 1)
                .put(CANCEL_RESERVATION_URL)
                .statusCode();
        Assert.assertTrue(unauthCancelStatus == 401 || unauthCancelStatus == 403);

        // 2. Signed-in Client can read own reservations -> 200
        given()
                .header("Authorization", "Bearer " + clientToken)
                .when()
                .get(MY_RESERVATIONS_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", isA(List.class));

        // 3. Signed-in Client MUST NOT view all members' reservations -> 403
        given()
                .header("Authorization", "Bearer " + clientToken)
                .when()
                .get(RESERVATIONS_URL)
                .then()
                .statusCode(403);

        // 4. Admin CAN view all members' reservations -> 200
        given()
                .header("Authorization", "Bearer " + adminToken)
                .when()
                .get(RESERVATIONS_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", isA(List.class));
    }


    // ==================== 4. Core Business Rules Tests ====================

    @Test(priority = 2, groups = {"smoke", "regression"})
    public void testReserveAvailableBookAndMissingInventoryRejected() {
        String userToken = registerFreshClientToken();

        // 1. Book with availableCopies > 0 MUST be rejected with 400 ("no need to reserve")
        int availableBookId = createBookWithInventory(2)[0];
        Response availRes = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", availableBookId))
                .post(RESERVATIONS_URL);

        Assert.assertEquals(
                availRes.statusCode(),
                400,
                "BUSINESS RULE BUG: Allowed reserving an in-stock book! Response: " + availRes.asString()
        );

        // 2. Book with NO inventory record at all MUST be rejected with 404
        int noInventoryBookId = createTempBook();
        Response noInvRes = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", noInventoryBookId))
                .post(RESERVATIONS_URL);

        Assert.assertEquals(
                noInvRes.statusCode(),
                404,
                "Expected 404 when reserving book without inventory record, got: " + noInvRes.asString()
        );
    }

    @Test(priority = 3, groups = {"smoke", "regression"})
    public void testReservationLifecycleDuplicateConflictCancelAndReReserve() {
        String userToken = registerFreshClientToken();
        int bookId = createBookWithInventory(0)[0];

        // 1. Create reservation on out-of-stock book -> 201 (status = WAITING)
        Response createRes = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(RESERVATIONS_URL);

        Assert.assertEquals(
                createRes.statusCode(),
                201,
                "Reservation creation failed: " + createRes.asString()
        );

        int reservationId = createRes.jsonPath().getInt("id");
        waitingReservationsToCleanup.add(new String[]{String.valueOf(reservationId), userToken});

        Assert.assertTrue(reservationId > 0);
        Assert.assertEquals(createRes.jsonPath().getInt("book.id"), bookId);
        Assert.assertEquals(createRes.jsonPath().getString("status"), "WAITING");
        Assert.assertNotNull(createRes.jsonPath().getString("reservationDate"));
        Assert.assertNull(createRes.jsonPath().get("fulfilledDate"));

        // 2. Duplicate active reservation by SAME user for SAME book -> 409 Conflict
        Response duplicateRes = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(RESERVATIONS_URL);

        Assert.assertEquals(
                duplicateRes.statusCode(),
                409,
                "BUSINESS RULE BUG: Allowed duplicate active reservation for same user and book! "
                        + duplicateRes.asString()
        );

        // 3. Cancel the WAITING reservation (PUT /api/reservations/{id}/cancel) -> 204
        given()
                .header("Authorization", "Bearer " + userToken)
                .pathParam("id", reservationId)
                .when()
                .put(CANCEL_RESERVATION_URL)
                .then()
                .statusCode(204);

        // 4. Double-cancel an already CANCELLED reservation -> 400 ("not in WAITING status")
        Response doubleCancelRes = given()
                .header("Authorization", "Bearer " + userToken)
                .pathParam("id", reservationId)
                .put(CANCEL_RESERVATION_URL);

        Assert.assertNotEquals(doubleCancelRes.statusCode(), 500, "Server crashed (500) on double cancel!");
        Assert.assertEquals(
                doubleCancelRes.statusCode(),
                400,
                "Expected 400 when cancelling non-WAITING reservation, got: " + doubleCancelRes.statusCode()
        );

        // 5. User can reserve the same book again once previous reservation is CANCELLED -> 201
        Response reReserveRes = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(RESERVATIONS_URL);

        Assert.assertEquals(
                reReserveRes.statusCode(),
                201,
                "User should be allowed to reserve again after cancelling previous reservation: "
                        + reReserveRes.asString()
        );
        waitingReservationsToCleanup.add(new String[]{String.valueOf(reReserveRes.jsonPath().getInt("id")), userToken});
    }


    // ==================== 5. FIFO Auto-Fulfillment & State Sync Rules ====================

    @Test(priority = 4, groups = "regression")
    public void testFifoWaitlistFulfillmentOldestFirstOnBookReturn() throws InterruptedException {
        String borrowerToken = registerFreshClientToken();
        String userAToken = registerFreshClientToken();
        String userBToken = registerFreshClientToken();

        // 1. Create book with 1 copy and borrow it so availableCopies drops to 0
        int bookId = createBookWithInventory(1)[0];

        int borrowId = given()
                .header("Authorization", "Bearer " + borrowerToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(BORROWS_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        activeBorrowsToCleanup.add(new String[]{String.valueOf(borrowId), borrowerToken});

        // 2. User A reserves FIRST (oldest waiting reservation)
        int resAId = given()
                .header("Authorization", "Bearer " + userAToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(RESERVATIONS_URL)
                .then()
                .statusCode(201)
                .body("status", equalTo("WAITING"))
                .extract()
                .path("id");
        waitingReservationsToCleanup.add(new String[]{String.valueOf(resAId), userAToken});

        Thread.sleep(60);

        // 3. User B reserves SECOND (newer waiting reservation)
        int resBId = given()
                .header("Authorization", "Bearer " + userBToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(RESERVATIONS_URL)
                .then()
                .statusCode(201)
                .body("status", equalTo("WAITING"))
                .extract()
                .path("id");
        waitingReservationsToCleanup.add(new String[]{String.valueOf(resBId), userBToken});

        // 4. Borrower returns the single copy -> stock becomes 1
        given()
                .header("Authorization", "Bearer " + borrowerToken)
                .pathParam("id", borrowId)
                .put(RETURN_BORROW_URL)
                .then()
                .statusCode(200);

        // 5. Sync & Verify FIFO: User A (oldest) MUST turn into FULFILLED with fulfilledDate populated
        // 5. Sync & Verify FIFO: User A (oldest) MUST turn into FULFILLED with fulfilledDate populated
        Response myResA = given()
                .header("Authorization", "Bearer " + userAToken)
                .get(MY_RESERVATIONS_URL);
        Assert.assertEquals(myResA.statusCode(), 200);

        List listA = myResA.jsonPath().getList("$");
        Map recordA = null;
        for (Object item : listA) {
            Map r = (Map) item;
            if (((Number) r.get("id")).intValue() == resAId) {
                recordA = r;
                break;
            }
        }
        Assert.assertNotNull(recordA, "User A reservation missing from /my");
        Assert.assertEquals(
                recordA.get("status"),
                "FULFILLED",
                "FIFO SYNC BUG: Oldest reservation (User A) did not transition to FULFILLED when stock returned!"
        );
        Assert.assertNotNull(recordA.get("fulfilledDate"), "fulfilledDate must be set when status becomes FULFILLED");

        // 6. Verify User B (second in queue) MUST stay WAITING because only 1 copy became available
        Response myResB = given()
                .header("Authorization", "Bearer " + userBToken)
                .get(MY_RESERVATIONS_URL);
        Assert.assertEquals(myResB.statusCode(), 200);

        List listB = myResB.jsonPath().getList("$");
        Map recordB = null;
        for (Object item : listB) {
            Map r = (Map) item;
            if (((Number) r.get("id")).intValue() == resBId) {
                recordB = r;
                break;
            }
        }
        Assert.assertNotNull(recordB, "User B reservation missing from /my");
        Assert.assertEquals(
                recordB.get("status"),
                "WAITING",
                "FIFO QUEUE BUG: Second reservation (User B) was prematurely fulfilled with only 1 copy returned!"
        );
        Assert.assertNull(recordB.get("fulfilledDate"));

        // 7. Cancelling User A's FULFILLED reservation MUST fail with 400 ("not in WAITING status")
        given()
                .header("Authorization", "Bearer " + userAToken)
                .pathParam("id", resAId)
                .when()
                .put(CANCEL_RESERVATION_URL)
                .then()
                .statusCode(400);
    }
    @Test(priority = 5, groups = "regression")
    public void testAdminInventoryRestockAlsoFulfillsWaitingReservation() {
        String userToken = registerFreshClientToken();
        int[] bookAndInv = createBookWithInventory(0);
        int bookId = bookAndInv[0];
        int invId = bookAndInv[1];

        // 1. User reserves out-of-stock book -> WAITING
        int resId = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(RESERVATIONS_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        waitingReservationsToCleanup.add(new String[]{String.valueOf(resId), userToken});

        // 2. Admin restocks inventory from 0 -> 1
        given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .pathParam("id", invId)
                .body(Map.of("availableCopies", 1))
                .put(INVENTORY_BY_ID_URL)
                .then()
                .statusCode(200);

        // 3. GET /api/reservations/my syncs status -> FULFILLED
        Response myRes = given()
                .header("Authorization", "Bearer " + userToken)
                .get(MY_RESERVATIONS_URL);
        Assert.assertEquals(myRes.statusCode(), 200);

        String syncedStatus = myRes.jsonPath().getString("find { it.id == " + resId + " }.status");
        Assert.assertEquals(
                syncedStatus,
                "FULFILLED",
                "Reservation did not auto-sync to FULFILLED after Admin restocked inventory!"
        );
    }


    // ==================== 6. Concurrency & Integrity Bug-Hunting ====================

    @Test(priority = 6, groups = {"regression", "security-audit"})
    public void testConcurrentDuplicateReservationsBySameUserBlocked() throws Exception {
        String userToken = registerFreshClientToken();
        int bookId = createBookWithInventory(0)[0];
        int threads = 5;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<Future<Response>>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return given()
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(ContentType.JSON)
                        .body(Map.of("bookId", bookId))
                        .post(RESERVATIONS_URL);
            }));
        }

        latch.countDown();

        int createdCount = 0;
        int conflictCount = 0;
        for (Future<Response> f : futures) {
            Response res = f.get();
            Assert.assertNotEquals(res.statusCode(), 500, "Server crashed (500) under concurrent reservations!");
            if (res.statusCode() == 201) {
                createdCount++;
                waitingReservationsToCleanup.add(new String[]{String.valueOf(res.jsonPath().getInt("id")), userToken});
            } else if (res.statusCode() == 409) {
                conflictCount++;
            }
        }
        executor.shutdown();

        Assert.assertEquals(
                createdCount,
                1,
                "CRITICAL CONCURRENCY BUG (TOCTOU): Same user created " + createdCount
                        + " active reservations for the exact same book!"
        );
        Assert.assertEquals(conflictCount, threads - 1);
    }

    @Test(priority = 7, groups = {"regression", "security-audit"})
    public void testOrphanedInventoryAndBookDeletionIntegrityWithReservations() {
        String userToken = registerFreshClientToken();
        int[] bookAndInv = createBookWithInventory(0);
        int bookId = bookAndInv[0];
        int invId = bookAndInv[1];

        // 1. Create active WAITING reservation
        int resId = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(RESERVATIONS_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        waitingReservationsToCleanup.add(new String[]{String.valueOf(resId), userToken});

        // 2. Attempting to delete book with active reservation should return 409/400 (never 500)
        Response deleteBookRes = BookEndPointes.deleteBook(bookId, adminToken);
        Assert.assertNotEquals(
                deleteBookRes.statusCode(),
                500,
                "Server crashed (500) deleting book with active reservation!"
        );

        // 3. Delete inventory record while reservation is WAITING, then trigger /my sync
        given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", invId)
                .delete(INVENTORY_BY_ID_URL)
                .then()
                .statusCode(204);
        createdInventoryIds.remove(Integer.valueOf(invId));

        Response syncAfterInvDelete = given()
                .header("Authorization", "Bearer " + userToken)
                .get(MY_RESERVATIONS_URL);

        Assert.assertNotEquals(
                syncAfterInvDelete.statusCode(),
                500,
                "BUG (500 NullPointerException): GET /api/reservations/my crashed after inventory record was deleted! "
                        + syncAfterInvDelete.asString()
        );
    }


    // ==================== 7. Data-Driven Valid & Invalid Tests ====================

    @Test(
            priority = 8,
            dataProvider = "createValidReservationData",
            groups = "regression"
    )
    public void testDataDrivenCreateValidReservations(JsonNode testData) {
        String testCase = testData.get("testCase").asText();
        int initialStock = testData.get("initialStock").asInt();
        boolean borrowFirst = testData.get("borrowFirst").asBoolean();
        int expectedStatus = testData.get("expectedStatus").asInt();

        String reserverToken = registerFreshClientToken();
        int bookId = createBookWithInventory(initialStock)[0];

        if (borrowFirst) {
            String borrowerToken = registerFreshClientToken();
            int borrowId = given()
                    .header("Authorization", "Bearer " + borrowerToken)
                    .contentType(ContentType.JSON)
                    .body(Map.of("bookId", bookId))
                    .post(BORROWS_URL)
                    .then()
                    .statusCode(201)
                    .extract()
                    .path("id");
            activeBorrowsToCleanup.add(new String[]{String.valueOf(borrowId), borrowerToken});
        }

        Response res = given()
                .header("Authorization", "Bearer " + reserverToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(RESERVATIONS_URL);

        Assert.assertEquals(
                res.statusCode(),
                expectedStatus,
                "Failed valid reservation case [" + testCase + "] -> Response: " + res.asString()
        );

        if (res.statusCode() == 201) {
            int resId = res.jsonPath().getInt("id");
            waitingReservationsToCleanup.add(new String[]{String.valueOf(resId), reserverToken});
            Assert.assertEquals(res.jsonPath().getString("status"), "WAITING");
        }
    }

    @Test(
            priority = 9,
            dataProvider = "createInvalidReservationData",
            groups = "regression"
    )
    @SuppressWarnings("unchecked")
    public void testDataDrivenCreateInvalidReservations(JsonNode testData) {
        String testCase = testData.get("testCase").asText();
        int expectedStatus = testData.get("expectedStatus").asInt();
        Map<String, Object> payload = mapper.convertValue(testData.get("payload"), Map.class);

        Response res = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(RESERVATIONS_URL);

        Assert.assertNotEquals(
                res.statusCode(),
                500,
                "Server crashed (500) on invalid reservation case: " + testCase
        );

        Assert.assertEquals(
                res.statusCode(),
                expectedStatus,
                "Failed invalid reservation case [" + testCase + "] -> Response: " + res.asString()
        );
    }


    // ==================== 8. Security & OWASP Audit Tests ====================

    @Test(priority = 10, groups = {"security", "regression"})
    public void testBolaOwnershipOnCancelAndIsolationAndNoPasswordLeak() {
        String ownerToken = registerFreshClientToken();
        String otherClientToken = registerFreshClientToken();
        int bookId = createBookWithInventory(0)[0];

        Response createRes = given()
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(RESERVATIONS_URL);
        Assert.assertEquals(createRes.statusCode(), 201);

        Assert.assertFalse(
                createRes.asString().contains("\"password\"") || createRes.asString().contains("passwordHash"),
                "SECURITY FINDING: Password leaked in POST /api/reservations response!"
        );

        int resId = createRes.jsonPath().getInt("id");
        waitingReservationsToCleanup.add(new String[]{String.valueOf(resId), ownerToken});

        // 1. Another Client CANNOT cancel owner's reservation -> 403
        given()
                .header("Authorization", "Bearer " + otherClientToken)
                .pathParam("id", resId)
                .when()
                .put(CANCEL_RESERVATION_URL)
                .then()
                .statusCode(403);

        // 2. Admin CANNOT cancel a member's reservation ("Only by its owner") -> 403
        given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", resId)
                .when()
                .put(CANCEL_RESERVATION_URL)
                .then()
                .statusCode(403);

        // 3. Another Client's /api/reservations/my MUST NOT contain owner's reservation
        List<Integer> otherClientReservations = given()
                .header("Authorization", "Bearer " + otherClientToken)
                .get(MY_RESERVATIONS_URL)
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        Assert.assertFalse(
                otherClientReservations.contains(resId),
                "CRITICAL BOLA BUG: Owner's reservation appeared in another user's /api/reservations/my!"
        );

        // 4. Admin's GET /api/reservations MUST include it and never leak passwords
        Response adminAllRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .get(RESERVATIONS_URL);
        Assert.assertEquals(adminAllRes.statusCode(), 200);
        Assert.assertFalse(adminAllRes.asString().contains("\"password\""));
        List<Integer> allIds = adminAllRes.jsonPath().getList("id");
        Assert.assertTrue(allIds.contains(resId));
    }

    @Test(priority = 11, groups = {"security", "regression"})
    public void testMassAssignmentIgnoredOnCreateReservation() {
        String userToken = registerFreshClientToken();
        int bookId = createBookWithInventory(0)[0];

        Map<String, Object> maliciousPayload = new HashMap<String, Object>();
        maliciousPayload.put("id", 999999);
        maliciousPayload.put("bookId", bookId);
        maliciousPayload.put("status", "FULFILLED");
        maliciousPayload.put("reservationDate", "2020-01-01T00:00:00");
        maliciousPayload.put("fulfilledDate", "2026-01-01T00:00:00");

        Response res = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(maliciousPayload)
                .post(RESERVATIONS_URL);

        Assert.assertEquals(res.statusCode(), 201, "Reservation failed: " + res.asString());

        int actualId = res.jsonPath().getInt("id");
        waitingReservationsToCleanup.add(new String[]{String.valueOf(actualId), userToken});

        Assert.assertNotEquals(actualId, 999999, "MASS ASSIGNMENT BUG: Client supplied reservation ID accepted!");
        Assert.assertEquals(res.jsonPath().getString("status"), "WAITING", "MASS ASSIGNMENT BUG: Spoofed status accepted!");
        Assert.assertNull(res.jsonPath().get("fulfilledDate"), "MASS ASSIGNMENT BUG: Spoofed fulfilledDate accepted!");
        Assert.assertNotEquals(res.jsonPath().getString("reservationDate"), "2020-01-01T00:00:00");
    }

    @Test(priority = 12, groups = {"security", "regression"})
    public void testTamperedAndUnsignedJwtRejectedOnReservations() {
        int bookId = createBookWithInventory(0)[0];
        Map<String, Object> payload = Map.of("bookId", bookId);

        // 1. Tampered JWT signature
        String tamperedToken = clientToken.substring(0, clientToken.length() - 4) + "ZZZZ";
        int tamperedStatus = given()
                .header("Authorization", "Bearer " + tamperedToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(RESERVATIONS_URL)
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
                .post(RESERVATIONS_URL)
                .statusCode();

        Assert.assertNotEquals(unsignedStatus, 500, "Server crashed (500) on unsigned JWT!");
        Assert.assertTrue(unsignedStatus == 401 || unsignedStatus == 403);
    }

    @Test(priority = 13, groups = {"security", "security-audit"})
    public void testNonExistentIdsOverflowUnsupportedMethodsAndMalformedJson() {
        int nonExistentId = 9999999;

        // 1. Non-existent reservation ID on cancel -> 404
        given()
                .header("Authorization", "Bearer " + clientToken)
                .pathParam("id", nonExistentId)
                .put(CANCEL_RESERVATION_URL)
                .then()
                .statusCode(404);

        // 2. Malformed JSON -> 400
        String brokenJson = "{\"bookId\": ";
        given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(brokenJson)
                .post(RESERVATIONS_URL)
                .then()
                .statusCode(400);

        // 3. Unsupported HTTP methods (must return 405, never 500)
        Response postCancelRes = given()
                .header("Authorization", "Bearer " + clientToken)
                .pathParam("id", 1)
                .post(CANCEL_RESERVATION_URL);
        Assert.assertNotEquals(postCancelRes.statusCode(), 500, "BUG (500 on POST /reservations/{id}/cancel): " + postCancelRes.asString());

        Response deleteRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .delete(RESERVATIONS_URL + "/1");
        Assert.assertNotEquals(deleteRes.statusCode(), 500, "BUG (500 on DELETE /reservations/{id}): " + deleteRes.asString());

        // 4. Path Injection & Long Overflow on cancel path -> Must not return 500 or stack trace
        String[] maliciousIds = {"99999999999999999999", "-1", "abc", "1'OR'1'='1"};
        for (String badId : maliciousIds) {
            Response res = given()
                    .header("Authorization", "Bearer " + clientToken)
                    .put(RESERVATIONS_URL + "/" + badId + "/cancel");

            String body = res.asString();
            Assert.assertNotEquals(
                    res.statusCode(),
                    500,
                    "BUG (500) on PUT /api/reservations/" + badId + "/cancel -> " + body
            );
            Assert.assertFalse(
                    body.contains("java.lang.") || body.contains("org.springframework."),
                    "SECURITY FINDING: Stack trace leaked for reservation ID [" + badId + "]: " + body
            );
        }
    }

    @Test(priority = 14, groups = "security-audit")
    public void testMyReservationsRateLimitingAudit() {
        int maxRequests = 50;
        int successCount = 0;
        int rateLimitedCount = 0;

        for (int i = 1; i <= maxRequests; i++) {
            Response res = given()
                    .header("Authorization", "Bearer " + clientToken)
                    .get(MY_RESERVATIONS_URL);

            int status = res.statusCode();
            Assert.assertNotEquals(status, 500, "Server crashed (500) on GET /api/reservations/my at request #" + i);

            if (status == 429) {
                rateLimitedCount++;
                break;
            } else if (status == 200) {
                successCount++;
            }
        }

        Assert.assertTrue(
                rateLimitedCount > 0,
                "SECURITY FINDING (OWASP API4): No Rate Limiting (429) enforced on GET /api/reservations/my after "
                        + successCount + " rapid requests!"
        );
    }


    // ==================== 9. Cleanup ====================

    @AfterClass(alwaysRun = true)
    public void cleanupReservationsBorrowsInventoryAndBooks() {
        // 1. Cancel any remaining WAITING reservations
        for (String[] entry : new ArrayList<String[]>(waitingReservationsToCleanup)) {
            try {
                given()
                        .header("Authorization", "Bearer " + entry[1])
                        .pathParam("id", Integer.parseInt(entry[0]))
                        .put(CANCEL_RESERVATION_URL);
            } catch (Exception ignored) {
            }
        }
        waitingReservationsToCleanup.clear();

        // 2. Return any active borrows
        for (String[] entry : new ArrayList<String[]>(activeBorrowsToCleanup)) {
            try {
                given()
                        .header("Authorization", "Bearer " + entry[1])
                        .pathParam("id", Integer.parseInt(entry[0]))
                        .put(RETURN_BORROW_URL);
            } catch (Exception ignored) {
            }
        }
        activeBorrowsToCleanup.clear();

        // 3. Delete inventory records
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

        // 4. Delete temporary books
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