package api.test.books;

import api.endpoints.*;
import api.payloads.BookRequest;
import api.payloads.InventoryRequest;
import api.test.Auth.BaseTest;
import api.utilites.RestAssuredLogFilter;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.*;
import java.util.concurrent.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

public class BooksCompleteCoverageTests extends BaseTest {
    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }
    // Tracks all books created during this suite for guaranteed cleanup
    private final List<Integer> createdBookIds = Collections.synchronizedList(new ArrayList<>());


    @Test(priority = 1)
    public void testBookResponseContractAndFieldTypes() {
        // Create a book with null optional fields to verify nullable contract behavior
        BookRequest minimalBook = new BookRequest(
                "Contract Schema Book",
                "Schema Author",
                2025,
                null,
                null,
                null,
                49.99,
                null
        );

        Response createRes = BookEndPointes.createBook(minimalBook, adminToken);
        Assert.assertEquals(createRes.statusCode(), 201);
        int bookId = createRes.jsonPath().getInt("id");
        createdBookIds.add(bookId);

        // Validate exact field presence, data types, and nullable fields on GET /api/books/{id}
        given()
                .pathParam("id", bookId)
                .when()
                .get(Routes.GET_BOOK_BY_ID)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("id", isA(Integer.class))
                .body("isbn", anyOf(isA(Integer.class), isA(Long.class)))
                .body("title", isA(String.class))
                .body("author", isA(String.class))
                .body("publicationYear", isA(Integer.class))
                .body("price", isA(Number.class))
                .body("publisher", anyOf(nullValue(), isA(String.class)))
                .body("genre", anyOf(nullValue(), isA(String.class)))
                .body("categoryId", anyOf(nullValue(), isA(Integer.class)))
                .body("description", anyOf(nullValue(), isA(String.class)));

        // Validate GET /api/books returns a non-empty JSON array with valid item contracts
        given()
                .when()
                .get(Routes.GET_ALL_BOOKS)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", isA(List.class))
                .body("size()", greaterThan(0))
                .body("[0].id", isA(Integer.class))
                .body("[0].title", isA(String.class))
                .body("[0].price", isA(Number.class));
    }
    @Test(priority = 2)
    public void testExactMaxLengthBoundariesAccepted() {
        String exactTitle200 = "T".repeat(200);
        String exactAuthor150 = "A".repeat(150);
        String exactPublisher150 = "P".repeat(150);
        String exactGenre100 = "G".repeat(100);
        String exactDescription1000 = "D".repeat(1000);

        BookRequest maxBoundaryBook = new BookRequest(
                exactTitle200,
                exactAuthor150,
                2024,
                exactPublisher150,
                exactGenre100,
                null,
                99.99,
                exactDescription1000
        );

        Response res = BookEndPointes.createBook(maxBoundaryBook, adminToken);
        Assert.assertEquals(res.statusCode(), 201, "Server rejected exact maximum allowed field lengths!");

        int bookId = res.jsonPath().getInt("id");
        createdBookIds.add(bookId);

        Assert.assertEquals(res.jsonPath().getString("title").length(), 200);
        Assert.assertEquals(res.jsonPath().getString("author").length(), 150);
        Assert.assertEquals(res.jsonPath().getString("publisher").length(), 150);
        Assert.assertEquals(res.jsonPath().getString("genre").length(), 100);
        Assert.assertEquals(res.jsonPath().getString("description").length(), 1000);
    }

    @Test(priority = 3)
    public void testMinimumPositivePriceAndYearBoundaries() {
        BookRequest minPriceBook = new BookRequest(
                "Penny Book", "Budget Author", 2024, null, null, null, 0.01, null
        );
        Response minPriceRes = BookEndPointes.createBook(minPriceBook, adminToken);
        Assert.assertEquals(minPriceRes.statusCode(), 201, "Server rejected minimum positive price 0.01!");
        createdBookIds.add(minPriceRes.jsonPath().getInt("id"));

        // 2. Invalid publicationYear boundaries (0 and negative)
        BookRequest negativeYearBook = new BookRequest(
                "Invalid Year Book", "Time Traveler", -500, null, null, null, 25.0, null
        );
        Response negYearRes = BookEndPointes.createBook(negativeYearBook, adminToken);
        Assert.assertNotEquals(negYearRes.statusCode(), 500, "Server crashed on negative publicationYear!");
        if (negYearRes.statusCode() == 201) {
            createdBookIds.add(negYearRes.jsonPath().getInt("id"));
        }
    }

    @DataProvider(name = "invalidDataTypePayloads")
    public Object[][] getInvalidDataTypePayloads() {
        return new Object[][]{
                {"String in price", Map.of("title", "Type Test", "author", "Author", "publicationYear", 2024, "price", "fifty")},
                {"String in publicationYear", Map.of("title", "Type Test", "author", "Author", "publicationYear", "twenty", "price", 20.0)},
                {"String in categoryId", Map.of("title", "Type Test", "author", "Author", "publicationYear", 2024, "categoryId", "abc", "price", 20.0)},
                {"Array in title", Map.of("title", List.of("ArrayTitle"), "author", "Author", "publicationYear", 2024, "price", 20.0)},
                {"Object in description", Map.of("title", "Type Test", "author", "Author", "publicationYear", 2024, "price", 20.0, "description", Map.of("nested", "obj"))},
                {"Empty JSON object", Map.of()}
        };
    }

    @Test(priority = 4, dataProvider = "invalidDataTypePayloads")
    public void testInvalidDataTypesRejectedCleanly(String caseName, Map<String, Object> malformedBody) {
        Response res = BookEndPointes.createBook(malformedBody, adminToken);
        Assert.assertEquals(
                res.statusCode(),
                400,
                "Expected 400 Bad Request for [" + caseName + "], but got: " + res.statusCode()
        );
    }


    @Test(priority = 6)
    public void testResetOptionalFieldsToNullAndIdempotency() {
        // 1. Create book with all optional fields populated
        BookRequest fullBook = new BookRequest(
                "Nullable Reset Book", "Author One", 2023, "Initial Publisher", "Drama", null, 60.0, "Initial description"
        );
        Response createRes = BookEndPointes.createBook(fullBook, adminToken);
        Assert.assertEquals(createRes.statusCode(), 201);
        int bookId = createRes.jsonPath().getInt("id");
        long originalIsbn = createRes.jsonPath().getLong("isbn");
        createdBookIds.add(bookId);
        BookRequest clearedOptionalFields = new BookRequest(
                "Nullable Reset Book Updated", "Author One", 2023, null, null, null, 65.0, null
        );
        Response update1 = BookEndPointes.updateBook(bookId, clearedOptionalFields, adminToken);
        Assert.assertEquals(update1.statusCode(), 200);
        Assert.assertNull(update1.jsonPath().get("publisher"), "Publisher should be cleared to null");
        Assert.assertNull(update1.jsonPath().get("genre"), "Genre should be cleared to null");
        Assert.assertNull(update1.jsonPath().get("description"), "Description should be cleared to null");
        Assert.assertEquals(update1.jsonPath().getLong("isbn"), originalIsbn, "ISBN must not change on update");

        Response update2 = BookEndPointes.updateBook(bookId, clearedOptionalFields, adminToken);
        Assert.assertEquals(update2.statusCode(), 200);
        Assert.assertEquals(update2.jsonPath().getString("title"), "Nullable Reset Book Updated");
        Assert.assertEquals(update2.jsonPath().getLong("isbn"), originalIsbn);

        Assert.assertEquals(BookEndPointes.deleteBook(bookId, adminToken).statusCode(), 204);
        createdBookIds.remove(Integer.valueOf(bookId));

        Assert.assertEquals(BookEndPointes.deleteBook(bookId, adminToken).statusCode(), 404, "Second delete must return 404");
        Assert.assertEquals(BookEndPointes.updateBook(bookId, clearedOptionalFields, adminToken).statusCode(), 404,
                "Updating a deleted book must return 404");
    }

    @Test(priority = 7)
    public void testDeleteBookConflictWhenLinkedToActiveEntities() {
        // Create a dedicated book + inventory to attach dependent entities
        BookRequest linkedBook = new BookRequest(
                "Referential Integrity Book", "FK Author", 2024, null, null, null, 75.0, null
        );
        int bookId = BookEndPointes.createBook(linkedBook, adminToken).jsonPath().getInt("id");
        createdBookIds.add(bookId);

        int invId = InventoryEndpoints.createInventory(new InventoryRequest(bookId, 5), adminToken)
                .jsonPath().getInt("id");

        int borrowId = 0;
        int reservationId = 0;

        try {
            // Create a Borrow and a Reservation on this book
            Response borrowRes = given()
                    .header("Authorization", "Bearer " + clientToken)
                    .contentType(ContentType.JSON)
                    .body(Map.of("bookId", bookId))
                    .post(Routes.CREATE_BORROW);
            if (borrowRes.statusCode() == 200 || borrowRes.statusCode() == 201) {
                borrowId = borrowRes.jsonPath().getInt("id");
            }

            Response resvRes = given()
                    .header("Authorization", "Bearer " + clientToken)
                    .contentType(ContentType.JSON)
                    .body(Map.of("bookId", bookId))
                    .post(Routes.CREATE_RESERVATION);
            if (resvRes.statusCode() == 200 || resvRes.statusCode() == 201) {
                reservationId = resvRes.jsonPath().getInt("id");
            }

            // Remove inventory first so the 409 check specifically isolates Borrow/Reservation FK constraints
            InventoryEndpoints.deleteInventory(invId, adminToken);
            invId = 0;

            Response deleteWithDependencies = BookEndPointes.deleteBook(bookId, adminToken);
            Assert.assertEquals(
                    deleteWithDependencies.statusCode(),
                    409,
                    "Expected 409 Conflict when deleting a book linked to active borrows/reservations!"
            );
        } finally {
            if (borrowId > 0) BorrowsEndpoints.returnBorrow(borrowId, clientToken);
            if (reservationId > 0) ReservationsEndpoints.cancelReservation(reservationId, clientToken);
            if (invId > 0) InventoryEndpoints.deleteInventory(invId, adminToken);
        }
    }
    @Test(priority = 9, groups = "security-audit")
    public void testCatalogReadBurstStabilityAndRateLimitObservation() {
        int successCount = 0;
        int rateLimitedCount = 0;

        for (int i = 0; i < 100; i++) {
            Response res = BookEndPointes.getAllBooks();
            int status = res.statusCode();

            Assert.assertNotEquals(status, 500, "GET /api/books crashed under rapid burst at request #" + (i + 1));
            if (status == 200) {
                successCount++;
            } else if (status == 429) {
                rateLimitedCount++;
            }
        }

        System.out.printf("Catalog Burst Result (30 reqs) -> 200 OK: %d | 429 Rate Limited: %d%n",
                successCount, rateLimitedCount);
        Assert.assertTrue(successCount + rateLimitedCount == 30, "Unexpected HTTP status during catalog burst!");
    }


    @AfterClass(alwaysRun = true)
    public void cleanupCreatedBooks() {
        for (Integer id : new ArrayList<>(createdBookIds)) {
            if (id != null && id > 0) {
                BookEndPointes.deleteBook(id, adminToken);
            }
        }
        createdBookIds.clear();
    }
}