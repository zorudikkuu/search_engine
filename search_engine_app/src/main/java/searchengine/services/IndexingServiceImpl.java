package searchengine.services;

import lombok.Getter;
import org.jsoup.Connection;
import org.springframework.stereotype.Service;
import searchengine.config.SiteDto;
import searchengine.config.SitesList;
import searchengine.config.WebConnection;
import searchengine.dto.responses.IndexingResponse;
import searchengine.model.IndexingStatus;
import searchengine.model.entities.Index;
import searchengine.model.entities.Lemma;
import searchengine.model.entities.Page;
import searchengine.model.entities.Site;
import searchengine.model.repositories.IndexRepository;
import searchengine.model.repositories.LemmaRepository;
import searchengine.model.repositories.PageRepository;
import searchengine.model.repositories.SiteRepository;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class IndexingServiceImpl implements IndexingService {
    private final SitesList sitesList;
    private ExecutorService executor;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private CountDownLatch latch;
    private final ForkJoinPool forkJoinPool = new ForkJoinPool();
    private final PageIndexer pageIndexer;

    public IndexingServiceImpl (SitesList sitesList, SiteRepository siteRepository,
                                PageRepository pageRepository, LemmaRepository lemmaRepository,
                                PageIndexer pageIndexer
    ) {
        this.sitesList = sitesList;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.lemmaRepository = lemmaRepository;
        this.pageIndexer = pageIndexer;
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
            executor.submit(() -> {
                executeSiteParsing(siteDto);
                latch.countDown();
            });
        }

        getWaitingThread().start();

        response.setResult(true);
        return response;
    }

    @Override
    public IndexingResponse stopIndexing() {
        IndexingResponse response = new IndexingResponse();
        if (!isIndexing(siteRepository.findAll())) {
            response.setResult(false);
            response.setError("Индексация не запущена");
            return response;
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

    @Override
    public IndexingResponse indexPage(String url) {
        IndexingResponse response = new IndexingResponse();
        latch = new CountDownLatch(1);

        String decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8);
        String absHref = decodedUrl.substring(decodedUrl.indexOf("=") + 1);
        int endDomainIndex = absHref.indexOf("/", absHref.indexOf("//") + 2);
        String domain = absHref.substring(0, endDomainIndex + 1);

        List<SiteDto> siteList = sitesList.getSites();
        SiteDto siteByUrl = null;
        for (SiteDto siteDto : siteList) {
            if (siteDto.getUrl().equals(domain)) {
                siteByUrl = siteDto;
            }
        }

        Site site;
        if (!(siteByUrl == null)) {
            site = siteRepository.findByUrl(domain).isPresent() ? siteRepository.findByUrl(domain).get() : saveSiteEntity(siteByUrl, IndexingStatus.INDEXED);
            siteRepository.save(site);
        } else {
            response.setResult(false);
            response.setError("Данная страница находится за пределами сайтов, " +
                    "указанных в конфигурационном файле");
            return response;
        }

        Optional<Page> optionalPage = pageRepository.findByPath(absHref.substring(endDomainIndex));
        optionalPage.ifPresent(this::clearPageInfo);

        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            pageIndexer.executePageIndexing(absHref, site);
            latch.countDown();
        });

        getWaitingThread().start();
        response.setResult(true);
        return response;
    }

    private void executeSiteParsing (SiteDto siteDto) {
        Optional<Site> siteOptional = siteRepository.findByUrl(siteDto.getUrl());
        siteOptional.ifPresent(site -> {
            List<Lemma> lemmasToDelete = lemmaRepository.findBySite(site);
            siteRepository.delete(site);
            lemmaRepository.deleteAll(lemmasToDelete);
        });
        Site indexingSite = saveSiteEntity(siteDto, IndexingStatus.INDEXING);

        WebParserTask task = new WebParserTask(
                indexingSite, indexingSite.getUrl(),
                siteRepository, pageRepository,
                new AtomicBoolean(true), pageIndexer
        );
        forkJoinPool.invoke(task);

        if (task.getIsIndexed().get()) {
            indexingSite.setIndexingStatus(IndexingStatus.INDEXED);
            indexingSite.setStatusTime(LocalDateTime.now());
            siteRepository.save(indexingSite);
        }
    }

    private Thread getWaitingThread () {
        return new Thread(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                System.err.println(e.getMessage());
            }
            System.out.println("Индексация завершена");
            executor.shutdown();
        });
    }

    private Site saveSiteEntity (SiteDto siteDto, IndexingStatus status) {
        Site site = new Site();
        site.setIndexingStatus(status);
        site.setStatusTime(LocalDateTime.now());
        site.setUrl(siteDto.getUrl());
        site.setName(siteDto.getName());
        siteRepository.save(site);
        return site;
    }

    private void clearPageInfo (Page page) {
        List<Lemma> lemmasToDelete = page.getIndexSet().stream().map(Index::getLemma).toList();
        pageRepository.delete(page);
        lemmasToDelete.forEach(lemma -> {
            if (lemma.getFrequency() == 1) {
                lemmaRepository.delete(lemma);
            } else {
                lemma.setFrequency(lemma.getFrequency() - 1);
                lemmaRepository.save(lemma);
            }
        });
    }

    private boolean isIndexing (List<Site> sites) {
        return sites
                .stream()
                .map(Site::getIndexingStatus)
                .anyMatch(status -> status.equals(IndexingStatus.INDEXING));
    }
}
