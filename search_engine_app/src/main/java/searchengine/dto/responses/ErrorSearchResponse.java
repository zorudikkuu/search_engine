package searchengine.dto.responses;

import lombok.Data;

@Data
public class ErrorSearchResponse {
    private boolean result;
    private String error;
    public ErrorSearchResponse (String error) {
        this.result = false;
        this.error = error;
    }
}
