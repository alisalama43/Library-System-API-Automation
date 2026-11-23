package api.payloads;

public class UpdateInventoryRequest {
    private Integer availableCopies;

    public UpdateInventoryRequest() {}

    public UpdateInventoryRequest(Integer availableCopies) {
        this.availableCopies = availableCopies;
    }

    public Integer getAvailableCopies() { return availableCopies; }
    public void setAvailableCopies(Integer availableCopies) { this.availableCopies = availableCopies; }
}