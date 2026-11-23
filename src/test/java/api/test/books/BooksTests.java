package api.test.books;

import api.endpoints.BookEndPointes;
import api.endpoints.CategoriesEndpoints;
import api.endpoints.InventoryEndpoints;
import api.payloads.BookRequest;
import api.payloads.CategoryRequest;
import api.payloads.InventoryRequest;
import api.test.Auth.BaseTest;
import api.utilites.JsonDataReader;
import api.utilites.RestAssuredLogFilter;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class BooksTests extends BaseTest {
    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }

    private static final String DATA_PATH = "src/test/resources/testdata/Booksdata.json";

    private int createdCategoryId;
    private int createdBookId1;
    private int createdBookId2;
    private long generatedIsbn1;

    @BeforeClass
    public void setupCategory() {
        CategoryRequest catPayload = new CategoryRequest(
                "BooksTestCat_" + System.currentTimeMillis(),
                "Temporary category for books automation"
        );
        Response res = CategoriesEndpoints.createCategory(catPayload, adminToken);
        if (res.statusCode() == 200 || res.statusCode() == 201) {
            createdCategoryId = res.jsonPath().getInt("id");
        } else {
            createdCategoryId = CategoriesEndpoints.getAllCategories().jsonPath().getInt("[0].id");
        }
    }

    // ==================== Data Providers ====================

    @DataProvider(name = "createBookData")
    public Object[][] getCreateBookData() throws IOException {
        return JsonDataReader.getJsonData(DATA_PATH, "createBookCases");
    }

    @DataProvider(name = "updateBookData")
    public Object[][] getUpdateBookData() throws IOException {
        return JsonDataReader.getJsonData(DATA_PATH, "updateBookCases");
    }

    @DataProvider(name = "deleteBookNegativeData")
    public Object[][] getDeleteBookNegativeData() throws IOException {
        return JsonDataReader.getJsonData(DATA_PATH, "deleteBookNegativeCases");
    }

    // ==================== Helper Methods ====================

    private String resolveDynamicString(Object rawValue) {
        if (rawValue == null) return null;
        String val = rawValue.toString();
        if (val.startsWith("REPEAT_")) {
            int count = Integer.parseInt(val.replace("REPEAT_", ""));
            return "A".repeat(count);
        }
        return val;
    }

    private BookRequest buildBookPayload(Map<String, Object> data) {
        String title = resolveDynamicString(data.get("title"));
        String author = resolveDynamicString(data.get("author"));
        String publisher = resolveDynamicString(data.get("publisher"));
        String genre = resolveDynamicString(data.get("genre"));
        String description = resolveDynamicString(data.get("description"));

        Integer publicationYear = data.get("publicationYear") != null
                ? ((Number) data.get("publicationYear")).intValue()
                : null;

        Double price = data.get("price") != null
                ? ((Number) data.get("price")).doubleValue()
                : null;

        Integer categoryId = null;
        String categoryMode = (String) data.get("categoryMode");
        if ("USE_VALID_CATEGORY".equals(categoryMode)) {
            categoryId = createdCategoryId;
        } else if ("INVALID_ID".equals(categoryMode)) {
            categoryId = 9999999;
        }

        return new BookRequest(title, author, publicationYear, publisher, genre, categoryId, price, description);
    }

    // ==================== 1. Create Book DDT ====================

    @Test(priority = 1, dataProvider = "createBookData")
    public void testCreateBookDataDriven(Map<String, Object> data) {
        String caseName = (String) data.get("testCaseName");
        String roleToken = (String) data.get("roleToken");
        int expectedStatus = ((Number) data.get("expectedStatusCode")).intValue();

        BookRequest payload = buildBookPayload(data);

        Response response;
        if ("ADMIN".equals(roleToken)) {
            response = BookEndPointes.createBook(payload, adminToken);
        } else if ("CLIENT".equals(roleToken)) {
            response = BookEndPointes.createBook(payload, clientToken);
        } else {
            response = BookEndPointes.createBookWithoutToken(payload);
        }

        // السيرفر قد يرجع 401 أو 403 عند غياب التوكن
        if ("NONE".equals(roleToken) && expectedStatus == 403) {
            Assert.assertTrue(
                    response.statusCode() == 401 || response.statusCode() == 403,
                    "Failed on case: " + caseName + " | Actual: " + response.statusCode()
            );
            return;
        }

        Assert.assertEquals(response.statusCode(), expectedStatus, "Failed on case: " + caseName);

        if (expectedStatus == 201) {
            int bookId = response.jsonPath().getInt("id");
            long isbn = response.jsonPath().getLong("isbn");

            Assert.assertTrue(bookId > 0, "Book ID must be generated");
            Assert.assertTrue(isbn > 0, "ISBN must be auto-generated");
            Assert.assertEquals(response.jsonPath().getString("title"), payload.getTitle());

            if (Boolean.TRUE.equals(data.get("saveBookId"))) {
                createdBookId1 = bookId;
                generatedIsbn1 = isbn;
            } else if (Boolean.TRUE.equals(data.get("saveSecondBookId"))) {
                createdBookId2 = bookId;
                Assert.assertTrue(
                        isbn > generatedIsbn1,
                        "Auto-generated ISBN (" + isbn + ") must be greater than previous ISBN (" + generatedIsbn1 + ")"
                );
            }
        }
    }

    // ==================== 2. Public Read Tests (GET) ====================

    @Test(priority = 2, dependsOnMethods = "testCreateBookDataDriven")
    public void testGetAllBooksPublic() {
        Response response = BookEndPointes.getAllBooks();

        Assert.assertEquals(response.statusCode(), 200);
        List<Integer> ids = response.jsonPath().getList("id");
        Assert.assertTrue(ids.contains(createdBookId1), "Catalog should include created book");
    }

    @Test(priority = 3, dependsOnMethods = "testCreateBookDataDriven")
    public void testGetBookByIdPublicAndNotFound() {
        Response validRes = BookEndPointes.getBookById(createdBookId1);
        Assert.assertEquals(validRes.statusCode(), 200);
        Assert.assertEquals(validRes.jsonPath().getInt("id"), createdBookId1);
        Assert.assertEquals(validRes.jsonPath().getLong("isbn"), generatedIsbn1);

        Response notFoundRes = BookEndPointes.getBookById(9999999);
        Assert.assertEquals(notFoundRes.statusCode(), 404);
    }


    @Test(priority = 4, dataProvider = "updateBookData", dependsOnMethods = "testCreateBookDataDriven")
    public void testUpdateBookDataDriven(Map<String, Object> data) {
        String caseName = (String) data.get("testCaseName");
        String roleToken = (String) data.get("roleToken");
        String targetBook = (String) data.get("targetBook");
        int expectedStatus = ((Number) data.get("expectedStatusCode")).intValue();

        int bookId = "CREATED_BOOK".equals(targetBook) ? createdBookId1 : 9999999;
        BookRequest payload = buildBookPayload(data);

        Response response;
        if ("ADMIN".equals(roleToken)) {
            response = BookEndPointes.updateBook(bookId, payload, adminToken);
        } else if ("CLIENT".equals(roleToken)) {
            response = BookEndPointes.updateBook(bookId, payload, clientToken);
        } else {
            response = BookEndPointes.updateBookWithoutToken(bookId, payload);
        }

        if ("NONE".equals(roleToken) && expectedStatus == 403) {
            Assert.assertTrue(
                    response.statusCode() == 401 || response.statusCode() == 403,
                    "Failed on case: " + caseName + " | Actual: " + response.statusCode()
            );
            return;
        }

        Assert.assertEquals(response.statusCode(), expectedStatus, "Failed on case: " + caseName);

        if (expectedStatus == 200) {
            Assert.assertEquals(response.jsonPath().getString("title"), payload.getTitle());
            Assert.assertEquals(response.jsonPath().getDouble("price"), payload.getPrice());
            Assert.assertEquals(
                    response.jsonPath().getLong("isbn"),
                    generatedIsbn1,
                    "ISBN must never change on update"
            );
        }
    }

    // ==================== 4. Delete Book DDT & 409 Conflict Check ====================

    @Test(priority = 5, dataProvider = "deleteBookNegativeData", dependsOnMethods = "testCreateBookDataDriven")
    public void testDeleteBookNegativeDataDriven(Map<String, Object> data) {
        String caseName = (String) data.get("testCaseName");
        String roleToken = (String) data.get("roleToken");
        String targetBook = (String) data.get("targetBook");
        int expectedStatus = ((Number) data.get("expectedStatusCode")).intValue();

        int bookId = "CREATED_BOOK".equals(targetBook) ? createdBookId1 : 9999999;

        Response response;
        if ("ADMIN".equals(roleToken)) {
            response = BookEndPointes.deleteBook(bookId, adminToken);
        } else if ("CLIENT".equals(roleToken)) {
            response = BookEndPointes.deleteBook(bookId, clientToken);
        } else {
            response = BookEndPointes.deleteBookWithoutToken(bookId);
        }

        if ("NONE".equals(roleToken) && expectedStatus == 403) {
            Assert.assertTrue(
                    response.statusCode() == 401 || response.statusCode() == 403,
                    "Failed on case: " + caseName + " | Actual: " + response.statusCode()
            );
            return;
        }

        Assert.assertEquals(response.statusCode(), expectedStatus, "Failed on case: " + caseName);
    }

    @Test(priority = 6, dependsOnMethods = "testCreateBookDataDriven")
    public void testDeleteReferencedBookReturns409Then204AfterCleanup() {
        // 1. ربط الكتاب بسجل مخزون
       InventoryRequest invPayload = new InventoryRequest(createdBookId1, 5);
        Response invRes = InventoryEndpoints.createInventory(invPayload, adminToken);
        Assert.assertTrue(invRes.statusCode() == 200 || invRes.statusCode() == 201);
        int inventoryId = invRes.jsonPath().getInt("id");

        // 2. محاولة الحذف والكتاب مرتبط بالمخزون -> 409 Conflict
        Response conflictRes = BookEndPointes.deleteBook(createdBookId1, adminToken);
        Assert.assertEquals(conflictRes.statusCode(), 409, "Referenced book must return 409 Conflict");

        // 3. حذف سجل المخزون ثم حذف الكتاب -> 204 No Content
        InventoryEndpoints.deleteInventory(inventoryId, adminToken);
        Response deleteRes = BookEndPointes.deleteBook(createdBookId1, adminToken);
        Assert.assertEquals(deleteRes.statusCode(), 204);

        // 4. التأكد من اختفاء الكتاب -> 404
        Assert.assertEquals(BookEndPointes.getBookById(createdBookId1).statusCode(), 404);
    }

    @AfterClass
    public void cleanup() {
        if (createdBookId2 > 0) {
            BookEndPointes.deleteBook(createdBookId2, adminToken);
        }
        if (createdCategoryId > 0) {
            CategoriesEndpoints.deleteCategory(createdCategoryId, adminToken);
        }
    }
}