package api.endpoints;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

public class BookEndPointes {

    private static RequestSpecification withOptionalToken(String token) {
        RequestSpecification req = given();
        if (token != null && !token.isEmpty()) {
            req.header("Authorization", "Bearer " + token);
        }
        return req;
    }

    // --- Public Endpoints ---
    public static Response getAllBooks() {
        return given()
                .when()
                .get(Routes.GET_ALL_BOOKS);
    }

    public static Response getBookById(int id) {
        return given()
                .pathParam("id", id)
                .when()
                .get(Routes.GET_BOOK_BY_ID);
    }

    // --- Authenticated Endpoints (Admin / Role Testing) ---
    public static Response createBook(Object requestBody, String token) {
        return withOptionalToken(token)
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post(Routes.CREATE_BOOK);
    }

    public static Response updateBook(int id, Object requestBody, String token) {
        return withOptionalToken(token)
                .pathParam("id", id)
                .contentType("application/json")
                .body(requestBody)
                .when()
                .put(Routes.UPDATE_BOOK);
    }

    public static Response deleteBook(int id, String token) {
        return withOptionalToken(token)
                .pathParam("id", id)
                .when()
                .delete(Routes.DELETE_BOOK);
    }

    // --- Unauthenticated Endpoints (For 401/403 Security Tests) ---
    public static Response createBookWithoutToken(Object requestBody) {
        return given()
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post(Routes.CREATE_BOOK);
    }

    public static Response updateBookWithoutToken(int id, Object requestBody) {
        return given()
                .pathParam("id", id)
                .contentType("application/json")
                .body(requestBody)
                .when()
                .put(Routes.UPDATE_BOOK);
    }

    public static Response deleteBookWithoutToken(int id) {
        return given()
                .pathParam("id", id)
                .when()
                .delete(Routes.DELETE_BOOK);
    }
}