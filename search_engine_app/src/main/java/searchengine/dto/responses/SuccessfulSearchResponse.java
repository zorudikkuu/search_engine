package searchengine.dto.responses;

import lombok.Data;
import searchengine.dto.search.SearchData;

import java.util.List;
import java.util.Set;

@Data
public class SuccessfulSearchResponse {
    private boolean result;
    private long count;
    private Set<SearchData> data;
}
