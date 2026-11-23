package api.payloads;

public class ReservationRequest {
    private Integer bookId;

    public ReservationRequest() {}

    public ReservationRequest(Integer bookId) {
        this.bookId = bookId;
    }

    public Integer getBookId() { return bookId; }
    public void setBookId(Integer bookId) { this.bookId = bookId; }
}