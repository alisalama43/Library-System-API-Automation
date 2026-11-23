package api.payloads;

public class InventoryRequest {
    private Integer bookId;
    private Integer availableCopies;

    public InventoryRequest() {}

    public InventoryRequest(Integer bookId, Integer availableCopies) {
        this.bookId = bookId;
        this.availableCopies = availableCopies;
    }

    public Integer getBookId() { return bookId; }
    public void setBookId(Integer bookId) { this.bookId = bookId; }

    public Integer getAvailableCopies() { return availableCopies; }
    public void setAvailableCopies(Integer availableCopies) { this.availableCopies = availableCopies; }
}