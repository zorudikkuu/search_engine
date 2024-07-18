package searchengine.dto.search;

import lombok.Data;

@Data
public class SearchData implements Comparable<SearchData> {
    private String site;
    private String siteName;
    private String uri;
    private String title;
    private String snippet;
    private double relevance;

    @Override
    public int compareTo(SearchData o) {
        return Double.compare(o.getRelevance(), this.getRelevance());
    }
}
