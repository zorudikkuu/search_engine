package searchengine.dto.search;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Comparator;

@Data
@EqualsAndHashCode
public class SearchData {
    private String site;
    private String siteName;
    private String uri;
    private String title;
    private String snippet;
    private double relevance;
}
