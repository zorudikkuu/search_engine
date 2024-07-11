package searchengine.services;

import lombok.Getter;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.model.IndexingStatus;
import searchengine.model.entities.Page;
import searchengine.model.entities.Site;
import searchengine.model.repositories.PageRepository;
import searchengine.model.repositories.SiteRepository;

import java.io.IOException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebParserTask extends RecursiveAction {

    private final Site site;
    private final String rootLink;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final PageIndexer pageIndexer;
    @Getter
    private AtomicBoolean isIndexed;
    private final List<WebParserTask> taskList = new ArrayList<>();
    @Getter
    private static final String[] invalidExtensions = new String[]{".png", ".svg", "jpg", "jpeg", ".gif", ".pdf", ".doc", ".docx", ".xlsx", ".eps", ".zip", ".yaml", ".yml", ".sql"};


    public WebParserTask (Site site, String rootLink,
                          SiteRepository siteRepository, PageRepository pageRepository,
                          AtomicBoolean isIndexed, PageIndexer pageIndexer) {
        this.site = site;
        this.rootLink = rootLink;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.isIndexed = isIndexed;
        this.pageIndexer = pageIndexer;
    }


    @Override
    protected void compute() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            failedIndexingResponse(e.getMessage() + " Индексация прервана, попробуйте еще раз.");
            return;
        }

        Document document;
        try {
            document = PageIndexer.getDocument(rootLink);
        } catch (IOException e) {
            failedIndexingResponse(e.getMessage() + "Не удалось подключиться к странице: " + rootLink);
            return;
        }

        Elements links = document.select("a");
        for (Element link : links) {
            String href = link.attr("href");
            String absHref = link.attr("abs:href");
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);

            if (linkIsValid(href, absHref)) {
                boolean isComplete = pageIndexer.executePageIndexing(absHref, site);
                if (!isComplete) {
                    failedIndexingResponse("Не удалось подключиться к странице: " + absHref);
                    return;
                }

                WebParserTask task = new WebParserTask(
                        site, absHref.replace("www.", ""),
                        siteRepository, pageRepository, isIndexed, pageIndexer
                );
                taskList.add(task);
            }
        }

        for (WebParserTask task : taskList) {
            task.fork();
        }
        for (WebParserTask task : taskList) {
            task.join();
        }
    }

    private boolean linkIsValid (String href, String absHref) {
        boolean hasValidExtension = Arrays.stream(invalidExtensions).noneMatch(extension -> href.toLowerCase().endsWith(extension));
        boolean isUnique = pageRepository.findByPath(href).isEmpty();

        return hasValidExtension && isUnique && absHref.startsWith(site.getUrl()) && !absHref.contains("#");
    }

    private void failedIndexingResponse (String errorMessage) {
        site.setIndexingStatus(IndexingStatus.FAILED);
        site.setStatusTime(LocalDateTime.now());
        site.setLastError(errorMessage);
        siteRepository.save(site);
        isIndexed.set(false);
    }
}
