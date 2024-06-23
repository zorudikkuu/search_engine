package searchengine.services;

import com.sun.source.tree.TryTree;
import org.aspectj.weaver.ast.Call;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.SiteDto;
import searchengine.config.SitesList;
import searchengine.dto.responses.IndexingResponse;
import searchengine.model.IndexingStatus;
import searchengine.model.entities.Site;
import searchengine.model.repositories.PageRepository;
import searchengine.model.repositories.SiteRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class IndexingServiceImpl implements IndexingService {
    private final SitesList sitesList;
    private final ExecutorService executor;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final CountDownLatch latch;
    public IndexingServiceImpl (SitesList sitesList, SiteRepository siteRepository, PageRepository pageRepository) {
        this.sitesList = sitesList;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.executor = Executors.newFixedThreadPool(sitesList.getSites().size());
        this.latch = new CountDownLatch(1);
    }

    @Override
    public IndexingResponse startIndexing() {
        IndexingResponse response = new IndexingResponse();
        List<SiteDto> sites = sitesList.getSites();
        if (isIndexing(siteRepository.findAll())) {
            response.setResult(false);
            response.setError("Индексация уже запущена");
            return response;
        }

        for (SiteDto siteDto : sites) {
            executor.submit(() -> {
                Optional<Site> site = siteRepository.findByUrl(siteDto.getUrl());
                site.ifPresent(siteRepository::delete);
                Site indexingSite = getSiteEntity(siteDto);

                WebParserTask task = new WebParserTask(indexingSite, indexingSite.getUrl(), siteRepository, pageRepository, new AtomicBoolean(true));
                ForkJoinPool forkJoinPool = new ForkJoinPool();
                forkJoinPool.invoke(task);
                if (task.getIsIndexing().get()) {
                    indexingSite.setIndexingStatus(IndexingStatus.INDEXED);
                    indexingSite.setStatusTime(LocalDateTime.now());
                    siteRepository.save(indexingSite);
                }

                latch.countDown();
                System.out.println("Count is down, current count is: " + latch.getCount());
            });
        }
        new Thread(() -> {
            System.out.println("Latch is waiting");
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
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

        executor.shutdownNow();
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
