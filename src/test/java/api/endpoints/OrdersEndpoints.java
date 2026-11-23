package api.endpoints;

import api.payloads.OrderRequest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;

public class OrdersEndpoints {

    public static Response createOrder(OrderRequest payload, String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post(Routes.CREATE_ORDER);
    }

    public static Response getMyOrders(String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get(Routes.GET_MY_ORDERS);
    }

    public static Response getOrderById(int id, String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .pathParam("id", id)
                .when()
                .get(Routes.GET_ORDER_BY_ID);
    }

    public static Response getAllOrders(String adminToken) {
        return given()
                .header("Authorization", "Bearer " + adminToken)
                .when()
                .get(Routes.GET_ALL_ORDERS);
    }
}