package api.test.category;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isA;

public class CategoriesTests extends BaseTest {
    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }

    private static final String CATEGORIES_URL =
            Routes.BASE_URL + "/api/categories";

    private static final String CATEGORY_BY_ID_URL =
            Routes.BASE_URL + "/api/categories/{id}";

    private final ObjectMapper mapper = new ObjectMapper();

    private final List<Integer> createdCategoryIds =
            Collections.synchronizedList(new ArrayList<Integer>());

    private final List<Integer> createdBookIds =
            Collections.synchronizedList(new ArrayList<Integer>());


    // ==================== Helper: Random Letters Generator ====================

    private String randomLetters(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }

        return sb.toString();
    }


    // ==================== 1. JSON Data Loader ====================

    private JsonNode loadTestData() {
        try {
            InputStream inputStream = getClass()
                    .getClassLoader()
                    .getResourceAsStream("testdata/categoriesdata.json");

            if (inputStream == null) {
                inputStream = getClass()
                        .getClassLoader()
                        .getResourceAsStream("categoriesdata.json");
            }

            if (inputStream != null) {
                try (InputStream is = inputStream) {
                    return mapper.readTree(is);
                }
            }

            File fallbackFile =
                    new File("src/test/resources/testdata/categoriesdata.json");

            if (!fallbackFile.exists()) {
                fallbackFile =
                        new File("src/test/resources/categoriesdata.json");
            }

            if (!fallbackFile.exists()) {
                throw new IOException(
                        "categoriesdata.json was not found in test resources"
                );
            }

            return mapper.readTree(fallbackFile);

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to load categoriesdata.json",
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

    @DataProvider(name = "updateInvalidData")
    public Object[][] updateInvalidData() {
        return extractCases("updateInvalidCases");
    }


    // ==================== 3. Smoke & Public Browsing Tests ====================

    @Test(priority = 1, groups = {"smoke", "regression"})
    public void testSmokeGetAllCategoriesPublicAccess() {
        given()
                .when()
                .get(CATEGORIES_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", isA(List.class));
    }


    // ==================== 4. Full CRUD Lifecycle & RBAC ====================

    @Test(priority = 2, groups = {"smoke", "regression"})
    public void testCompleteCategoryCrudLifecycleAndPermissions() {
        String initialName = "History " + randomLetters(6);

        Map createBody = Map.of(
                "name", initialName,
                "description", "World and ancient history books"
        );

        // Client cannot create category -> 403
        int clientPostStatus = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(createBody)
                .post(CATEGORIES_URL)
                .statusCode();

        Assert.assertEquals(
                clientPostStatus,
                403,
                "Client role must not create categories"
        );

        // Unauthenticated user cannot create category -> 401 or 403
        int noTokenPostStatus = given()
                .contentType(ContentType.JSON)
                .body(createBody)
                .post(CATEGORIES_URL)
                .statusCode();

        Assert.assertTrue(
                noTokenPostStatus == 401 || noTokenPostStatus == 403
        );

        // Admin creates category -> 201
        Response createRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(createBody)
                .post(CATEGORIES_URL);

        Assert.assertEquals(
                createRes.statusCode(),
                201,
                "Create category failed: " + createRes.asString()
        );

        int categoryId = createRes.jsonPath().getInt("id");
        createdCategoryIds.add(categoryId);

        Assert.assertTrue(categoryId > 0);
        Assert.assertEquals(
                createRes.jsonPath().getString("name"),
                initialName
        );
        Assert.assertEquals(
                createRes.jsonPath().getString("description"),
                "World and ancient history books"
        );

        // Read category by ID -> 200
        given()
                .pathParam("id", categoryId)
                .when()
                .get(CATEGORY_BY_ID_URL)
                .then()
                .statusCode(200)
                .body("id", equalTo(categoryId))
                .body("name", equalTo(initialName))
                .body(
                        "description",
                        equalTo("World and ancient history books")
                );

        // Client cannot update category -> 403
        String updatedName = "Modern History " + randomLetters(6);

        Map updateBody = Map.of(
                "name", updatedName,
                "description", "Modern and contemporary world history"
        );

        int clientPutStatus = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .pathParam("id", categoryId)
                .body(updateBody)
                .put(CATEGORY_BY_ID_URL)
                .statusCode();

        Assert.assertEquals(
                clientPutStatus,
                403,
                "Client role must not update categories"
        );

        // Admin updates category -> 200
        Response updateRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .pathParam("id", categoryId)
                .body(updateBody)
                .put(CATEGORY_BY_ID_URL);

        Assert.assertEquals(
                updateRes.statusCode(),
                200,
                "Update category failed: " + updateRes.asString()
        );
        Assert.assertEquals(
                updateRes.jsonPath().getString("name"),
                updatedName
        );
        Assert.assertEquals(
                updateRes.jsonPath().getString("description"),
                "Modern and contemporary world history"
        );

        // Client cannot delete category -> 403
        int clientDeleteStatus = given()
                .header("Authorization", "Bearer " + clientToken)
                .pathParam("id", categoryId)
                .delete(CATEGORY_BY_ID_URL)
                .statusCode();

        Assert.assertEquals(
                clientDeleteStatus,
                403,
                "Client role must not delete categories"
        );

        // Admin deletes category -> 204
        Response deleteRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", categoryId)
                .delete(CATEGORY_BY_ID_URL);

        Assert.assertEquals(deleteRes.statusCode(), 204);
        createdCategoryIds.remove(Integer.valueOf(categoryId));

        // Verify deleted category returns 404
        given()
                .pathParam("id", categoryId)
                .when()
                .get(CATEGORY_BY_ID_URL)
                .then()
                .statusCode(404);
    }


    // ==================== 5. Category Uniqueness ====================

    @Test(priority = 3, groups = "regression")
    public void testCategoryUniquenessConstraintsOnCreateAndUpdate() {
        String cat1Name = "Philosophy " + randomLetters(6);
        String cat2Name = "Psychology " + randomLetters(6);

        // Create Category 1
        int cat1Id = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "name", cat1Name,
                        "description", "First category"
                ))
                .post(CATEGORIES_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        createdCategoryIds.add(cat1Id);

        // Create Category 2
        int cat2Id = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "name", cat2Name,
                        "description", "Second category"
                ))
                .post(CATEGORIES_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        createdCategoryIds.add(cat2Id);

        // Duplicate category name must return 409
        given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "name", cat1Name,
                        "description", "Duplicate name attempt"
                ))
                .when()
                .post(CATEGORIES_URL)
                .then()
                .statusCode(409);

        // Updating category with another existing name must return 409
        given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .pathParam("id", cat2Id)
                .body(Map.of(
                        "name", cat1Name,
                        "description", "Renaming to another existing category"
                ))
                .when()
                .put(CATEGORY_BY_ID_URL)
                .then()
                .statusCode(409);

        // Updating category with its own name must succeed -> 200
        given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .pathParam("id", cat1Id)
                .body(Map.of(
                        "name", cat1Name,
                        "description", "Updated description keeping same name"
                ))
                .when()
                .put(CATEGORY_BY_ID_URL)
                .then()
                .statusCode(200)
                .body(
                        "description",
                        equalTo("Updated description keeping same name")
                );
    }


    // ==================== 6. Delete Category With Assigned Books ====================

    @Test(priority = 4, groups = "regression")
    public void testDeleteCategoryRejectedWith400WhenBooksAreAssigned() {
        String categoryName = "Linked Category " + randomLetters(6);

        // Create category
        int categoryId = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "name", categoryName,
                        "description", "Category with assigned book"
                ))
                .post(CATEGORIES_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        createdCategoryIds.add(categoryId);

        // Create book assigned to category
        BookRequest bookAssignedToCategory = new BookRequest(
                "Categorized Book " + randomLetters(6),
                "Domain Author",
                2025,
                "Domain Press",
                "General",
                categoryId,
                55.0,
                "Book linked to category for delete restriction test"
        );

        Response bookRes =
                BookEndPointes.createBook(
                        bookAssignedToCategory,
                        adminToken
                );

        Assert.assertEquals(
                bookRes.statusCode(),
                201,
                "Book creation failed: " + bookRes.asString()
        );

        int bookId = bookRes.jsonPath().getInt("id");
        createdBookIds.add(bookId);

        // Category deletion must be rejected with 400 while a book is assigned
        given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", categoryId)
                .when()
                .delete(CATEGORY_BY_ID_URL)
                .then()
                .statusCode(400);

        // Category must still exist
        given()
                .pathParam("id", categoryId)
                .when()
                .get(CATEGORY_BY_ID_URL)
                .then()
                .statusCode(200);

        // Delete book first
        Response deleteBookRes =
                BookEndPointes.deleteBook(bookId, adminToken);

        Assert.assertEquals(
                deleteBookRes.statusCode(),
                204
        );

        createdBookIds.remove(Integer.valueOf(bookId));

        // Now category deletion should succeed -> 204
        given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", categoryId)
                .when()
                .delete(CATEGORY_BY_ID_URL)
                .then()
                .statusCode(204);

        createdCategoryIds.remove(Integer.valueOf(categoryId));
    }


    // ==================== 7. Data-Driven Valid & Boundary Tests ====================

    @Test(
            priority = 5,
            dataProvider = "createValidAndBoundaryData",
            groups = "regression"
    )
    @SuppressWarnings("unchecked")
    public void testDataDrivenCreateValidAndExactBoundaryCases(
            JsonNode testData
    ) {
        String testCase = testData.get("testCase").asText();
        boolean makeNameUnique = testData.path("makeNameUnique").asBoolean(false);
        int expectedStatus = testData.get("expectedStatus").asInt();

        Map payload = mapper.convertValue(testData.get("payload"), Map.class);

        if (makeNameUnique && payload.get("name") != null) {
            payload.put(
                    "name",
                    payload.get("name") + " " + randomLetters(6)
            );
        }

        Response res = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(CATEGORIES_URL);

        Assert.assertEquals(
                res.statusCode(),
                expectedStatus,
                "Failed test case [" + testCase + "] -> Response: "
                        + res.asString()
        );

        if (res.statusCode() == 201) {
            int createdId = res.jsonPath().getInt("id");
            createdCategoryIds.add(createdId);

            Assert.assertEquals(
                    res.jsonPath().getString("name"),
                    payload.get("name")
            );
        }
    }


    // ==================== 8. Data-Driven Invalid Create Tests ====================

    @Test(
            priority = 6,
            dataProvider = "createInvalidAndBoundaryData",
            groups = "regression"
    )
    public void testDataDrivenCreateInvalidAndOutOfBoundsCases(
            JsonNode testData
    ) {
        String testCase = testData.get("testCase").asText();
        int expectedStatus = testData.get("expectedStatus").asInt();
        Map payload = mapper.convertValue(testData.get("payload"), Map.class);

        Response res = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(CATEGORIES_URL);

        Assert.assertNotEquals(
                res.statusCode(),
                500,
                "Server crashed (500) on test case: " + testCase
        );

        Assert.assertEquals(
                res.statusCode(),
                expectedStatus,
                "Failed invalid test case [" + testCase + "] -> Response: "
                        + res.asString()
        );
    }


    // ==================== 9. Data-Driven Invalid Update Tests ====================

    @Test(
            priority = 7,
            dataProvider = "updateInvalidData",
            groups = "regression"
    )
    public void testDataDrivenUpdateInvalidCases(
            JsonNode testData
    ) {
        // Create temporary valid category using letters only
        int targetId = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "name", "Update Target " + randomLetters(6),
                        "description", "Initial description"
                ))
                .post(CATEGORIES_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        createdCategoryIds.add(targetId);

        String testCase = testData.get("testCase").asText();
        int expectedStatus = testData.get("expectedStatus").asInt();
        Map payload = mapper.convertValue(testData.get("payload"), Map.class);

        Response res = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .pathParam("id", targetId)
                .body(payload)
                .put(CATEGORY_BY_ID_URL);

        Assert.assertNotEquals(
                res.statusCode(),
                500,
                "Server crashed (500) on update test case: " + testCase
        );

        Assert.assertEquals(
                res.statusCode(),
                expectedStatus,
                "Failed invalid update case [" + testCase + "] -> Response: "
                        + res.asString()
        );
    }


    // ==================== 10. Non-Existent IDs & Malformed JSON ====================

    @Test(priority = 8, groups = "regression")
    public void testNonExistentCategoryIdAndMalformedJson() {
        int nonExistentId = 9999999;

        // GET non-existent category -> 404
        given()
                .pathParam("id", nonExistentId)
                .get(CATEGORY_BY_ID_URL)
                .then()
                .statusCode(404);

        // PUT non-existent category -> 404
        given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .pathParam("id", nonExistentId)
                .body(Map.of(
                        "name", "Ghost Category " + randomLetters(6),
                        "description", "Does not exist"
                ))
                .put(CATEGORY_BY_ID_URL)
                .then()
                .statusCode(404);

        // DELETE non-existent category -> 404
        given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", nonExistentId)
                .delete(CATEGORY_BY_ID_URL)
                .then()
                .statusCode(404);

        // Malformed JSON -> 400
        String brokenJson = "{\"name\": \"BrokenJson\"";

        given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(brokenJson)
                .post(CATEGORIES_URL)
                .then()
                .statusCode(400);
    }


    // ==================== 11. Cleanup ====================

    @AfterClass(alwaysRun = true)
    public void cleanupCategoriesAndBooks() {
        // Delete books first so category deletion is not blocked
        for (Integer bookId : new ArrayList<Integer>(createdBookIds)) {
            if (bookId != null && bookId > 0) {
                try {
                    Response response =
                            BookEndPointes.deleteBook(bookId, adminToken);

                    System.out.println(
                            "Cleanup Book " + bookId
                                    + " => " + response.statusCode()
                    );
                } catch (Exception e) {
                    System.err.println(
                            "Failed to cleanup Book " + bookId
                                    + ": " + e.getMessage()
                    );
                }
            }
        }
        createdBookIds.clear();

        // Delete categories after books
        for (Integer categoryId : new ArrayList<Integer>(createdCategoryIds)) {
            if (categoryId != null && categoryId > 0) {
                try {
                    Response response = given()
                            .header(
                                    "Authorization",
                                    "Bearer " + adminToken
                            )
                            .pathParam("id", categoryId)
                            .delete(CATEGORY_BY_ID_URL);

                    System.out.println(
                            "Cleanup Category " + categoryId
                                    + " => " + response.statusCode()
                    );
                } catch (Exception e) {
                    System.err.println(
                            "Failed to cleanup Category " + categoryId
                                    + ": " + e.getMessage()
                    );
                }
            }
        }
        createdCategoryIds.clear();
    }
}