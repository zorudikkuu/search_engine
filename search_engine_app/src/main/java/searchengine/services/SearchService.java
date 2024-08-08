package searchengine.services;

import searchengine.dto.responses.SearchResponse;
import searchengine.dto.responses.SuccessfulSearchResponse;

public interface SearchService {
    SearchResponse search (String query, String siteUrl, int offset, int limit);
}
