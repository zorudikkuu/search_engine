package searchengine.dto.responses;

import lombok.Data;

@Data
public class IndexingResponse {
    private boolean result;
    private String error;
}
