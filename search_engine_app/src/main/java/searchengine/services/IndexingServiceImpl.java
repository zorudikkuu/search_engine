package searchengine.services;

import lombok.EqualsAndHashCode;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import searchengine.config.SiteDto;
import searchengine.config.SitesList;
import searchengine.dto.exceptions.NoSiteInConfigException;
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
import searchengine.utils.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Log4j2
@Service
@EqualsAndHashCode
public class IndexingServiceImpl implements IndexingService {
    private final SitesList sitesList;
    private ExecutorService executor;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final ForkJoinPool forkJoinPool = new ForkJoinPool();
    private final PageIndexer pageIndexer;
    private final TextParser textParser;
    private final AtomicBoolean isIndexingBool = new AtomicBoolean();
    private CountDownLatch latch;

    public IndexingServiceImpl (SiteRepository siteRepository, PageRepository pageRepository,
                                LemmaRepository lemmaRepository, IndexRepository indexRepository,
                                PageIndexer pageIndexer, TextParserImpl textParser, SitesList sitesList
                                ) {
        this.sitesList = sitesList;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.lemmaRepository = lemmaRepository;
        this.indexRepository = indexRepository;
        this.pageIndexer = pageIndexer;
        this.textParser = textParser;
    }

    @Override
    public IndexingResponse startIndexing() {
        IndexingResponse response = new IndexingResponse();
        if (isIndexingBool.get()) {
            response.setResult(false);
            response.setError("Индексация уже запущена");
            return response;
        }
        isIndexingBool.set(true);

        List<SiteDto> sites = sitesList.getSites();
        executor = Executors.newFixedThreadPool(sites.size());
        latch = new CountDownLatch(sites.size());

        for (SiteDto siteDto : sites) {
            executor.submit(() -> executeSiteParsing(siteDto));
        }
        waitIndexing();

        response.setResult(true);
        return response;
    }

    private void waitIndexing () {
        new Thread(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            isIndexingBool.set(false);
        }).start();
    }

    @Override
    public IndexingResponse stopIndexing() {
        IndexingResponse response = new IndexingResponse();
        if (!isIndexingBool.get()) {
            response.setResult(false);
            response.setError("Индексация не запущена");
            return response;
        }

        forkJoinPool.shutdownNow();
        try {
            if (forkJoinPool.awaitTermination(30, TimeUnit.SECONDS)) {
                log.info("ForkJoinPool terminated");
            }
        } catch (InterruptedException e) {
            log.error("Termination failed");
        }
        executor.shutdownNow();
        isIndexingBool.set(false);
        log.info("Индексация прервана");

        updateStoppedSites();
        response.setResult(true);
        return response;
    }

    @Override
    public IndexingResponse indexPage(String url) {
        IndexingResponse response = new IndexingResponse();

        String decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8);
        String absHref = decodedUrl.substring(decodedUrl.indexOf("=") + 1);
        int endDomainIndex = absHref.indexOf("/", absHref.indexOf("//") + 2);
        String domain = absHref.substring(0, endDomainIndex + 1);

        Site site;
        try {
            site = findSiteInConfiguration(domain);
        } catch (NoSiteInConfigException e) {
            response.setResult(false);
            response.setError("Данная страница находится за " +
                    "пределами сайтов, указанных в " +
                    "конфигурационном файле");
            return response;
        }

        Optional<Page> optionalPage = pageRepository.findByPathAndSite(absHref.substring(endDomainIndex), site);
        optionalPage.ifPresent(this::clearPageInfo);

        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            pageIndexer.executePageIndexing(absHref, site);
        });
        response.setResult(true);
        return response;
    }

    private void executeSiteParsing (SiteDto siteDto) {
        Optional<Site> siteOptional = siteRepository.findByUrl(siteDto.getUrl());
        siteOptional.ifPresent(site -> {
            siteRepository.delete(site);
            log.info("Сайт удален из БД");
        });
        Site indexingSite = saveSiteEntity(siteDto, IndexingStatus.INDEXING);

        WebParserTask task = new WebParserTask(
                indexingSite, indexingSite.getUrl(),
                siteRepository, pageRepository,
                new AtomicBoolean(true), pageIndexer
        );
        forkJoinPool.invoke(task);

        if (task.getIsIndexed().get()) {
            indexingSite.setStatusTime(LocalDateTime.now());
            indexingSite.setIndexingStatus(IndexingStatus.INDEXED);
            siteRepository.save(indexingSite);
        }
        latch.countDown();
    }



    private Site findSiteInConfiguration (String link) throws NoSiteInConfigException {
        List<SiteDto> siteList = sitesList.getSites();
        SiteDto siteByUrl = null;
        for (SiteDto siteDto : siteList) {
            if (siteDto.getUrl().equals(link)) {
                siteByUrl = siteDto;
            }
        }

        Site site;
        if (siteByUrl != null) {
            Optional<Site> optionalSite = siteRepository.findByUrl(link);
            site = optionalSite.isPresent() ? optionalSite.get() : saveSiteEntity(siteByUrl, IndexingStatus.INDEXED);
            siteRepository.save(site);
        } else {
            throw new NoSiteInConfigException("Данная страница находится за пределами сайтов, " +
                    "указанных в конфигурационном файле"
            );
        }

        return site;
    }

    private void updateStoppedSites () {
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
    }

    private Site saveSiteEntity (SiteDto siteDto, IndexingStatus status) {
        Site site = new Site();
        site.setIndexingStatus(status);
        site.setStatusTime(LocalDateTime.now());
        site.setUrl(siteDto.getUrl());
        site.setName(siteDto.getName());
        siteRepository.save(site);
        log.info("Сайт сохранен в БД");
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
}
