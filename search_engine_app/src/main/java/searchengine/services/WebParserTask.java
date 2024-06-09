package searchengine.services;

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
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebParserTask extends RecursiveAction {
    private static volatile Site site;
    private final String rootLink;
    private static final Set<String> allLinks = new ConcurrentSkipListSet<>();
    private static SiteRepository siteRepository;
    private static PageRepository pageRepository;
    private final AtomicBoolean isIndexing;
    public WebParserTask (Site site, String rootLink, SiteRepository siteRepository, PageRepository pageRepository, AtomicBoolean isIndexing) {
        WebParserTask.site = site;
        WebParserTask.siteRepository = siteRepository;
        WebParserTask.pageRepository = pageRepository;
        this.rootLink = rootLink;
        this.isIndexing = isIndexing;
        allLinks.add(rootLink);
    }

    @Override
    protected void compute() {
        if(!isIndexing.get()) {
            return;
        }

        List<WebParserTask> taskList = new ArrayList<>();
        Set<String> linkSet = findLinks(rootLink);

        for (String link : linkSet) {
            Page page = getPage(link);
            site.addPage(page);
            site.setStatusTime(new Timestamp(new Date().getTime()));

            siteRepository.save(site);
            pageRepository.save(page);
            System.out.println("save pages and site");

            WebParserTask newParser = new WebParserTask(site, link, siteRepository, pageRepository, isIndexing);
            newParser.fork();
            taskList.add(newParser);
        }

        for (WebParserTask task : taskList) {
            task.join();
        }
        site.setStatusTime(new Timestamp(new Date().getTime()));
        site.setIndexingStatus(IndexingStatus.INDEXED);
        siteRepository.save(site);
    }

    public void setIsIndexing(AtomicBoolean isIndexing) {
        this.isIndexing.set(false);
    }

    private Page getPage (String link) {
        Page page = null;
        try {
            page = new Page();
            Document document = getConnection(link);
            page.setSite(site);
            page.setCode(document.connection().execute().statusCode());
            page.setPath(link.replaceAll(site.getUrl(), ""));
            page.setContent(document.html());
        } catch (IOException e) {
            site.setStatusTime(new Timestamp(new Date().getTime()));
            site.setIndexingStatus(IndexingStatus.FAILED);
            site.setLastError(e.getMessage() + " --> Ошибка при подключении к странице!");
            siteRepository.save(site);
        }
        return page;
    }

    public Set<String> findLinks (String rootLink) {
        Document document = getConnection(rootLink);
        Elements links = document.select("a");
        Set<String> linkSet = new HashSet<>();
        for (Element element : links) {
            String link = element.absUrl("href");
            if (isDomain(link) && isUnique(link) && !link.startsWith("#") &&
                    !link.contains(".png") && !link.contains(".pdf")
                    && !link.contains(".jpg") && !link.startsWith(";")) {
                linkSet.add(link.replaceAll("/$", ""));
                allLinks.add(link);
            }
        }
        return linkSet;
    }

    private Document getConnection (String link) {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        Document document = null;
        try {
            document = Jsoup
                    .connect(link)
                    .userAgent("Mozilla/5.0 (Windows; U; WindowsNT 5.1; en-US; rv1.8.1.6) Gecko/20070725 Firefox/2.0.0.6")
                    .referrer("http://www.google.com")
                    .ignoreContentType(true)
                    .get();
        } catch (IOException e) {
            site.setStatusTime(new Timestamp(new Date().getTime()));
            site.setIndexingStatus(IndexingStatus.FAILED);
            site.setLastError(e.getMessage() + " --> Ошибка при подключении к странице!");
            siteRepository.save(site);
        }
        return document;
    }

    //метод говорит, ведет ли ссылка на страничку того же сайта
    private boolean isDomain (String url) {
        if (!url.contains("/")) {
            return false;
        }
        String[] rootDomain = rootLink.split("/");
        String[] linkDomain = url.split("/");
        return rootDomain[2].equals(linkDomain[2]) && !rootLink.equals(url);
    }


    //метод проверяет ссылку на уникальность
    private boolean isUnique (String url) {
        for (String link : allLinks ) {
            if (url.equals(link)) {
                return false;
            }
        }
        return true;
    }
}
