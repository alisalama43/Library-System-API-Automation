package api.endpoints;

import api.payloads.InventoryRequest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;

public class InventoryEndpoints {

    public static Response getAllInventory() {
        return given()
                .when()
                .get(Routes.GET_ALL_INVENTORY);
    }

    public static Response getAllInventory(String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get(Routes.GET_ALL_INVENTORY);
    }

    public static Response getInventoryById(int id) {
        return given()
                .pathParam("id", id)
                .when()
                .get(Routes.GET_INVENTORY_BY_ID);
    }

    public static Response getInventoryById(int id, String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .pathParam("id", id)
                .when()
                .get(Routes.GET_INVENTORY_BY_ID);
    }

    public static Response getInventoryByBookId(int bookId) {
        return given()
                .pathParam("bookId", bookId)
                .when()
                .get(Routes.GET_INVENTORY_BY_BOOK_ID);
    }

    public static Response getInventoryByBookId(int bookId, String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .pathParam("bookId", bookId)
                .when()
                .get(Routes.GET_INVENTORY_BY_BOOK_ID);
    }

    public static Response createInventory(InventoryRequest payload, String adminToken) {
        return given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post(Routes.CREATE_INVENTORY);
    }

    public static Response updateInventory(int id, InventoryRequest payload, String adminToken) {
        return given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .pathParam("id", id)
                .body(payload)
                .when()
                .put(Routes.UPDATE_INVENTORY);
    }

    public static Response deleteInventory(int id, String adminToken) {
        return given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", id)
                .when()
                .delete(Routes.DELETE_INVENTORY);
    }
}