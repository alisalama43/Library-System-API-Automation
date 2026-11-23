package api.payloads;

public class BorrowRequest {
    private Integer bookId;

    public BorrowRequest() {}

    public BorrowRequest(Integer bookId) {
        this.bookId = bookId;
    }

    public Integer getBookId() { return bookId; }
    public void setBookId(Integer bookId) { this.bookId = bookId; }
}