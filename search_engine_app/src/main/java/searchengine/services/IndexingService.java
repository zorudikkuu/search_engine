package searchengine.services;

import searchengine.dto.responses.IndexingResponse;

public interface IndexingService {
    IndexingResponse startIndexing () throws InterruptedException;
    IndexingResponse stopIndexing ();
}
