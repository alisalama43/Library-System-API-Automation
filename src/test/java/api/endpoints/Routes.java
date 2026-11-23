
package api.endpoints;

public class Routes {

    public static final String BASE_URL =
            "https://library-system-production-09db.up.railway.app";

    public static final String REGISTER = BASE_URL + "/api/auth/register";
    public static final String LOGIN = BASE_URL + "/api/auth/login";
    public static final String ADMIN_REGISTER = BASE_URL + "/api/auth/admin/register";


    public static final String GET_ALL_BOOKS = BASE_URL + "/api/books";
    public static final String GET_BOOK_BY_ID = BASE_URL + "/api/books/{id}";
    public static final String CREATE_BOOK = BASE_URL + "/api/books";
    public static final String UPDATE_BOOK = BASE_URL + "/api/books/{id}";
    public static final String DELETE_BOOK = BASE_URL + "/api/books/{id}";


    public static final String GET_ALL_CATEGORIES = BASE_URL + "/api/categories";
    public static final String GET_CATEGORY_BY_ID = BASE_URL + "/api/categories/{id}";
    public static final String CREATE_CATEGORY = BASE_URL + "/api/categories";
    public static final String UPDATE_CATEGORY = BASE_URL + "/api/categories/{id}";
    public static final String DELETE_CATEGORY = BASE_URL + "/api/categories/{id}";

    public static final String GET_ALL_INVENTORY = BASE_URL + "/api/inventory";
    public static final String GET_INVENTORY_BY_ID = BASE_URL + "/api/inventory/{id}";
    public static final String GET_INVENTORY_BY_BOOK_ID = BASE_URL + "/api/inventory/book/{bookId}";
    public static final String CREATE_INVENTORY = BASE_URL + "/api/inventory";
    public static final String UPDATE_INVENTORY = BASE_URL + "/api/inventory/{id}";
    public static final String DELETE_INVENTORY = BASE_URL + "/api/inventory/{id}";


    public static final String CREATE_BORROW = BASE_URL + "/api/borrows";
    public static final String RETURN_BORROW = BASE_URL + "/api/borrows/{id}/return";
    public static final String GET_MY_BORROWS = BASE_URL + "/api/borrows/my";
    public static final String GET_BORROW_BY_ID = BASE_URL + "/api/borrows/{id}";
    public static final String GET_ALL_BORROWS = BASE_URL + "/api/borrows";


    // ================= ORDERS =================
    public static final String CREATE_ORDER = BASE_URL + "/api/orders";
    public static final String GET_MY_ORDERS = BASE_URL + "/api/orders/my";
    public static final String GET_ORDER_BY_ID = BASE_URL + "/api/orders/{id}";
    public static final String GET_ALL_ORDERS = BASE_URL + "/api/orders";


    // ================= RESERVATIONS =================
    public static final String CREATE_RESERVATION = BASE_URL + "/api/reservations";
    public static final String CANCEL_RESERVATION = BASE_URL + "/api/reservations/{id}/cancel";
    public static final String GET_MY_RESERVATIONS = BASE_URL + "/api/reservations/my";
    public static final String GET_ALL_RESERVATIONS = BASE_URL + "/api/reservations";


    // ================= REVIEWS =================
    public static final String GET_REVIEWS_BY_BOOK_ID = BASE_URL + "/api/reviews/book/{bookId}";
    public static final String GET_BOOK_REVIEWS_AVERAGE = BASE_URL + "/api/reviews/book/{bookId}/average";
    public static final String CREATE_REVIEW = BASE_URL + "/api/reviews";
    public static final String UPDATE_REVIEW = BASE_URL + "/api/reviews/{id}";
    public static final String DELETE_REVIEW = BASE_URL + "/api/reviews/{id}";
    public static final String GET_MY_REVIEWS = BASE_URL + "/api/reviews/my";


    // ================= ACTIVITY =================
    public static final String GET_MY_ACTIVITY_SUMMARY =
            BASE_URL + "/api/users/me/activity-summary";

    public static final String GET_USER_ACTIVITY_SUMMARY =
            BASE_URL + "/api/users/{id}/activity-summary";

}
