package api.endpoints;

import api.payloads.CategoryRequest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;

public class CategoriesEndpoints {
    public static Response getAllCategories() {
        return given()
                .when()
                .get(Routes.GET_ALL_CATEGORIES);
    }

    public static Response getCategoryById(int id) {
        return given()
                .pathParam("id", id)
                .when()
                .get(Routes.GET_CATEGORY_BY_ID);
    }

    public static Response createCategory(CategoryRequest payload, String adminToken) {
        return given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post(Routes.CREATE_CATEGORY);
    }

    public static Response updateCategory(int id, CategoryRequest payload, String adminToken) {
        return given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .pathParam("id", id)
                .body(payload)
                .when()
                .put(Routes.UPDATE_CATEGORY);
    }

    public static Response deleteCategory(int id, String adminToken) {
        return given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", id)
                .when()
                .delete(Routes.DELETE_CATEGORY);
    }
}