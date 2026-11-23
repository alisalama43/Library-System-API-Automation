package api.payloads;

public class CreateReviewRequest {
    private Integer bookId;
    private Integer rating;
    private String comment;

    public CreateReviewRequest() {}

    public CreateReviewRequest(Integer bookId, Integer rating, String comment) {
        this.bookId = bookId;
        this.rating = rating;
        this.comment = comment;
    }

    public Integer getBookId() { return bookId; }
    public void setBookId(Integer bookId) { this.bookId = bookId; }

    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}