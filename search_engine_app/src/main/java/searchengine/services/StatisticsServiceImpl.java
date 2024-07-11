package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.config.SiteDto;
import searchengine.config.SitesList;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.responses.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.IndexingStatus;
import searchengine.model.entities.Site;
import searchengine.model.repositories.SiteRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {
    
    private final SitesList sites;
    private final SiteRepository siteRepository;

    @Override
    public StatisticsResponse getStatistics() {

        TotalStatistics total = new TotalStatistics();
        total.setIndexing(false);
        total.setSites(0);
        total.setPages(0);
        total.setLemmas(0);

        List<DetailedStatisticsItem> detailed = new ArrayList<>();
        List<SiteDto> sitesList = sites.getSites();
        for (SiteDto siteDto : sitesList) {
            Optional<Site> optionalSite = siteRepository.findByUrl(siteDto.getUrl());
            if (optionalSite.isEmpty()) {
                continue;
            }
            
            Site site = optionalSite.get();
            if (site.getIndexingStatus().equals(IndexingStatus.INDEXING)) {
                total.setIndexing(true);
            }
            
            total.setSites(total.getSites() + 1);
            total.setPages(total.getPages() + site.getPageSet().size());
            total.setLemmas(total.getLemmas() + site.getLemmaSet().size());
            
            DetailedStatisticsItem detailedItem = getDetailedStatisticItem(site);
            detailed.add(detailedItem);
        }

        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        data.setDetailed(detailed);
        response.setStatistics(data);
        response.setResult(true);
        return response;
    }

    private static DetailedStatisticsItem getDetailedStatisticItem (Site site) {
        DetailedStatisticsItem detailedItem = new DetailedStatisticsItem();
        detailedItem.setUrl(site.getUrl());
        detailedItem.setName(site.getName());
        detailedItem.setStatus(String.valueOf(site.getIndexingStatus()));
        detailedItem.setStatusTime(site.getStatusTime().getDayOfYear());
        detailedItem.setError(site.getLastError() == null ? "" : site.getLastError());
        detailedItem.setPages(site.getPageSet().size());
        detailedItem.setLemmas(site.getLemmaSet().size());
        return detailedItem;
    }
}
