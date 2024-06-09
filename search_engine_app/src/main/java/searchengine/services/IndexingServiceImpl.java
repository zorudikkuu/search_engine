package searchengine.services;

import com.sun.source.tree.TryTree;
import org.springframework.stereotype.Service;
import searchengine.config.SiteDto;
import searchengine.config.SitesList;
import searchengine.dto.responses.IndexingResponse;
import searchengine.model.IndexingStatus;
import searchengine.model.entities.Site;
import searchengine.model.repositories.PageRepository;
import searchengine.model.repositories.SiteRepository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class IndexingServiceImpl implements IndexingService {
    private final SitesList sitesList;
    private final ExecutorService executor;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final CountDownLatch countDownLatch;
    private WebParserTask task;
    public IndexingServiceImpl (SitesList sitesList, SiteRepository siteRepository, PageRepository pageRepository) {
        this.sitesList = sitesList;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.executor = Executors.newFixedThreadPool(sitesList.getSites().size());
        this.countDownLatch = new CountDownLatch(sitesList.getSites().size());
    }

    @Override
    public IndexingResponse startIndexing() {
        IndexingResponse response = new IndexingResponse();
        List<SiteDto> sites = sitesList.getSites();
        List<Site> existingSites = getExistingSites(sites);

        if (isIndexing(existingSites)) {
            response.setResult(false);
            response.setError("Индексация уже запущена");
            return response;
        }

        executor.submit(() -> {
            existingSites.forEach(site -> {
                siteRepository.delete(site);
                pageRepository.deleteAll(site.getPageSet());
            });
            for (SiteDto siteDto : sites) {
                executeSiteIndexing(siteDto);
            }
            countDownLatch.countDown();
        });

        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            System.out.println(e.getMessage());
        }
        task.setIsIndexing(new AtomicBoolean(false));
        executor.shutdown();

        response.setResult(true);
        return response;
    }

    @Override
    public IndexingResponse stopIndexing() {
        IndexingResponse response = new IndexingResponse();
        if (!isIndexing(siteRepository.findAll())) {
            response.setResult(false);
            response.setError("Индексация не запущена");
        }
        task.setIsIndexing(new AtomicBoolean(false));
        executor.shutdown();
        siteRepository
                .findAll()
                .stream()
                .filter(site -> site.getIndexingStatus().equals(IndexingStatus.INDEXING))
                .forEach(site -> {
                    site.setIndexingStatus(IndexingStatus.FAILED);
                    site.setLastError("Индексация остановлена пользователем");
                    site.setStatusTime(new Timestamp(new Date().getTime()));
                    siteRepository.save(site);
                });
        response.setResult(true);
        return response;
    }

    private void executeSiteIndexing (SiteDto siteDto) {
        Site site = new Site();
        site.setIndexingStatus(IndexingStatus.INDEXING);
        site.setStatusTime(new Timestamp(new Date().getTime()));
        site.setUrl(siteDto.getUrl());
        site.setName(siteDto.getName());
        siteRepository.save(site);

        task = new WebParserTask(site, site.getUrl(), siteRepository, pageRepository, new AtomicBoolean(true));
        ForkJoinPool forkJoinPool = new ForkJoinPool();
        forkJoinPool.execute(task);
    }

    private boolean isIndexing (List<Site> sites) {
        return sites
                .stream()
                .map(Site::getIndexingStatus)
                .anyMatch(status -> status.equals(IndexingStatus.INDEXING));

    }

    private List<Site> getExistingSites (List<SiteDto> sites) {
        List<Site> existingSites = new ArrayList<>();
        sites.forEach(siteDto -> existingSites.addAll(siteRepository.findByName(siteDto.getName())));
        return existingSites;
    }
}
