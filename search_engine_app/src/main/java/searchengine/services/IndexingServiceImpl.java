package searchengine.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.config.SiteDto;
import searchengine.config.SitesList;
import searchengine.config.WebConnection;
import searchengine.dto.responses.IndexingResponse;
import searchengine.model.IndexingStatus;
import searchengine.model.entities.Site;
import searchengine.model.repositories.PageRepository;
import searchengine.model.repositories.SiteRepository;

import java.time.LocalDateTime;
import java.util.List;

import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class IndexingServiceImpl implements IndexingService {
    private final SitesList sitesList;
    private ExecutorService executor;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private CountDownLatch latch;
    private final ForkJoinPool forkJoinPool = new ForkJoinPool();
    private final WebConnection webConnection;

    public IndexingServiceImpl (SitesList sitesList, SiteRepository siteRepository, PageRepository pageRepository, WebConnection webConnection) {
        this.sitesList = sitesList;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.webConnection = webConnection;
    }

    @Override
    public IndexingResponse startIndexing() {
        IndexingResponse response = new IndexingResponse();
        if (isIndexing(siteRepository.findAll())) {
            response.setResult(false);
            response.setError("Индексация уже запущена");
            return response;
        }

        List<SiteDto> sites = sitesList.getSites();
        executor = Executors.newFixedThreadPool(sitesList.getSites().size());
        latch = new CountDownLatch(sitesList.getSites().size());

        for (SiteDto siteDto : sites) {
            executor.submit(() -> executeSiteParsing(siteDto));
        }

        new Thread(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                System.err.println(e.getMessage());
            }

            executor.shutdown();
            System.out.println("Индексация завершена");
        }).start();

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

        forkJoinPool.shutdownNow();
        try {
            if (forkJoinPool.awaitTermination(30, TimeUnit.SECONDS)) {
                System.out.println("fjp terminated");
            }
        } catch (InterruptedException e) {
            System.err.println(e.getMessage() + " (Termination failed)");
        }
        executor.shutdownNow();
        System.out.println("Индексация прервана");

        siteRepository
                .findAll()
                .stream()
                .filter(site -> site.getIndexingStatus().equals(IndexingStatus.INDEXING))
                .forEach(site -> {
                    site.setIndexingStatus(IndexingStatus.FAILED);
                    site.setLastError("Индексация остановлена пользователем");
                    site.setStatusTime(LocalDateTime.now());
                    siteRepository.save(site);
                });
        response.setResult(true);
        return response;
    }

    private void executeSiteParsing (SiteDto siteDto) {
        Optional<Site> site = siteRepository.findByUrl(siteDto.getUrl());
        site.ifPresent(siteRepository::delete);
        Site indexingSite = getSiteEntity(siteDto);

        WebParserTask task = new WebParserTask(indexingSite, indexingSite.getUrl(), siteRepository, pageRepository, new AtomicBoolean(true), webConnection);
        forkJoinPool.invoke(task);

        if (task.getIsIndexed().get()) {
            indexingSite.setIndexingStatus(IndexingStatus.INDEXED);
            indexingSite.setStatusTime(LocalDateTime.now());
            siteRepository.save(indexingSite);
        }

        latch.countDown();
    }

    private Site getSiteEntity (SiteDto siteDto) {
        Site site = new Site();
        site.setIndexingStatus(IndexingStatus.INDEXING);
        site.setStatusTime(LocalDateTime.now());
        site.setUrl(siteDto.getUrl());
        site.setName(siteDto.getName());
        siteRepository.save(site);
        return site;
    }

    private boolean isIndexing (List<Site> sites) {
        return sites
                .stream()
                .map(Site::getIndexingStatus)
                .anyMatch(status -> status.equals(IndexingStatus.INDEXING));
    }
}
