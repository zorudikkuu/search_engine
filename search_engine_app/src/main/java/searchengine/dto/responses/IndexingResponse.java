package searchengine.dto.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IndexingResponse {
    private boolean result;
    private String error;
}
