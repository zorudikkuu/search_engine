package searchengine.services;

import searchengine.dto.responses.IndexingResponse;
import searchengine.dto.responses.SuccessfulSearchResponse;

public interface IndexingService {
    IndexingResponse startIndexing ();
    IndexingResponse stopIndexing ();
    IndexingResponse indexPage (String url);
}
