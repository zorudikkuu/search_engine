package searchengine.services;

import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import searchengine.model.IndexingStatus;
import searchengine.model.entities.Page;
import searchengine.model.entities.Site;
import searchengine.model.repositories.PageRepository;
import searchengine.model.repositories.SiteRepository;

import java.io.IOException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebParserTask extends RecursiveAction {
    private final Site site;
    private final String rootLink;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    @Getter
    private AtomicBoolean isIndexing;
    private final List<WebParserTask> taskList = new ArrayList<>();

    public WebParserTask (Site site, String rootLink, SiteRepository siteRepository, PageRepository pageRepository, AtomicBoolean isIndexing) {
        this.site = site;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.isIndexing = isIndexing;
        this.rootLink = rootLink;
    }

    @Override
    protected void compute() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        if (!isIndexing.get()) {
            return;
        }

        Document document;
        Connection.Response response;
        try {
            response = getResponse(rootLink);
            document = getDocument(rootLink);
        } catch (IOException e) {
            site.setIndexingStatus(IndexingStatus.FAILED);
            site.setStatusTime(LocalDateTime.now());
            site.setLastError(e.getMessage() + " --> Ошибка при подключении к странице! (" + rootLink + ")");
            siteRepository.save(site);
            isIndexing.set(false);
            return;
        }

        Elements links = document.select("a");
        for (Element link : links) {
            String href = link.attr("href");
            String absHref = link.attr("abs:href");
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);

            if (
                    absHref.startsWith(site.getUrl()) && !absHref.contains("#") && pageRepository.findByPath(href).isEmpty() &&
                            !href.toLowerCase().endsWith(".png") && !href.toLowerCase().endsWith(".svg") &&
                            !href.toLowerCase().endsWith(".jpg") && !href.toLowerCase().endsWith(".jpeg") &&
                            !href.toLowerCase().endsWith(".pdf") && !href.toLowerCase().endsWith(".docx") &&
                            !absHref.contains("%") && !absHref.contains("?")
            ) {
                Page pageEntity = getPage(href, response.statusCode(), document.html());
                pageRepository.save(pageEntity);
                WebParserTask task = new WebParserTask(site, absHref.replace("www.", ""), siteRepository, pageRepository, isIndexing);
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

    private Connection.Response getResponse (String link) throws IOException {
        return Jsoup
                .connect(link)
                .timeout(60000)
                .userAgent("Mozilla/5.0 (Windows; U; WindowsNT 5.1; en-US; rv1.8.1.6) Gecko/20070725 Firefox/2.0.0.6")
                .referrer("http://www.google.com")
                .execute();
    }

    private Document getDocument (String link) throws IOException {
        return Jsoup
                .connect(link)
                .timeout(60000)
                .userAgent("Mozilla/5.0 (Windows; U; WindowsNT 5.1; en-US; rv1.8.1.6) Gecko/20070725 Firefox/2.0.0.6")
                .referrer("http://www.google.com")
                .get();
    }

    private Page getPage (String link, int responseCode, String content) {
        Page page = new Page();
        page.setSite(site);
        page.setCode(responseCode);
        page.setPath(link);
        page.setContent(content);
        return page;
    }

    public void setIsIndexing(boolean newValue) {
        this.isIndexing.set(newValue);
    }
}
