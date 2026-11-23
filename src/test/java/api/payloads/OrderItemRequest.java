package api.payloads;

public class OrderItemRequest {
    private Integer bookId;
    private Integer quantity;

    public OrderItemRequest() {}

    public OrderItemRequest(Integer bookId, Integer quantity) {
        this.bookId = bookId;
        this.quantity = quantity;
    }

    public Integer getBookId() { return bookId; }
    public void setBookId(Integer bookId) { this.bookId = bookId; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
}