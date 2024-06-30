package searchengine.services;

import lombok.Getter;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.config.WebConnection;
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

    private static WebConnection connectionAssets;
    private final Site site;
    private final String rootLink;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    @Getter
    private AtomicBoolean isIndexed;
    private final List<WebParserTask> taskList = new ArrayList<>();
    private static final List<String> invalidExtensions = Arrays.asList(".png", ".svg", "jpg", "jpeg", ".gif", ".pdf", ".doc", ".docx", ".xlsx", ".eps", ".zip");

    public WebParserTask (Site site, String rootLink, SiteRepository siteRepository, PageRepository pageRepository, AtomicBoolean isIndexed, WebConnection webConnection) {
        this.site = site;
        this.rootLink = rootLink;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.isIndexed = isIndexed;
        connectionAssets = webConnection;
    }

    public WebParserTask (Site site, String rootLink, SiteRepository siteRepository, PageRepository pageRepository, AtomicBoolean isIndexed) {
        this.site = site;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.isIndexed = isIndexed;
        this.rootLink = rootLink;
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
        Connection.Response response;
        try {
            response = getResponse(rootLink);
            document = getDocument(rootLink);
        } catch (IOException e) {
            failedIndexingResponse(e.getMessage() + " --> Ошибка при подключении к странице. (" + rootLink + ")");
            return;
        }

        Elements links = document.select("a");
        for (Element link : links) {
            String href = link.attr("href");
            String absHref = link.attr("abs:href");
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);

            if (linkIsValid(href, absHref)) {
                Page pageEntity = getPage(href, response.statusCode(), document.html());
                pageRepository.save(pageEntity);
                WebParserTask task = new WebParserTask(site, absHref.replace("www.", ""), siteRepository, pageRepository, isIndexed);
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
        boolean hasValidExtension = invalidExtensions.stream().noneMatch(extension -> href.toLowerCase().endsWith(extension));
        boolean isUnique = pageRepository.findByPath(href).isEmpty();
        //boolean notRequest = !absHref.contains("&") && !absHref.contains("?") && !absHref.contains("%");

        return hasValidExtension && isUnique && absHref.startsWith(site.getUrl()) && !absHref.contains("#");
    }

    private Connection.Response getResponse (String link) throws IOException {
        return Jsoup
                .connect(link)
                .timeout(connectionAssets.getTimeout())
                .userAgent(connectionAssets.getAgent())
                .referrer(connectionAssets.getReferrer())
                .execute();
    }

    private Document getDocument (String link) throws IOException {
        return Jsoup
                .connect(link)
                .timeout(connectionAssets.getTimeout())
                .userAgent(connectionAssets.getAgent())
                .referrer(connectionAssets.getReferrer())
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

    private void failedIndexingResponse (String errorMessage) {
        site.setIndexingStatus(IndexingStatus.FAILED);
        site.setStatusTime(LocalDateTime.now());
        site.setLastError(errorMessage);
        siteRepository.save(site);
        isIndexed.set(false);
    }
}
