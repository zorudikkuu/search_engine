package searchengine.services;

import searchengine.dto.responses.IndexingResponse;

public interface IndexingService {
    IndexingResponse startIndexing ();
    IndexingResponse stopIndexing ();
    IndexingResponse indexPage (String url);
}
