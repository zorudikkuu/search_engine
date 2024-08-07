package searchengine.utils;

import lombok.extern.log4j.Log4j2;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.WebConnection;
import searchengine.model.entities.Index;
import searchengine.model.entities.Lemma;
import searchengine.model.entities.Page;
import searchengine.model.entities.Site;
import searchengine.model.repositories.IndexRepository;
import searchengine.model.repositories.LemmaRepository;
import searchengine.model.repositories.PageRepository;

import java.io.IOException;
import java.util.*;

@Log4j2
@Component
public class PageIndexer {
    private final TextParser textParser;
    private static WebConnection webConnection = new WebConnection();
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    public PageIndexer (TextParser textParser, PageRepository pageRepository,
                        LemmaRepository lemmaRepository, IndexRepository indexRepository,
                        WebConnection webConnection) {
        this.textParser = textParser;
        this.pageRepository = pageRepository;
        this.lemmaRepository = lemmaRepository;
        this.indexRepository = indexRepository;
        PageIndexer.webConnection = webConnection;
    }

    @Transactional
    public boolean executePageIndexing (String link, Site site) {
        Page page = getPageEntity(link, site);
        if (page == null) {
            return false;
        }

        pageRepository.save(page);
        log.info("Страница сохранена в БД");
        HashMap<String, Integer> lemmas = new HashMap<>(textParser.getLemmas(page.getContent()));
        Set<Lemma> lemmaSet = new HashSet<>();
        Set<Index> indexSet = new HashSet<>();
        for (Map.Entry<String, Integer> lemmaToCount : lemmas.entrySet()) {
            Lemma lemma = getLemmaEntity(lemmaToCount.getKey(), site);
            Index index = getIndexEntity(page, lemma, lemmaToCount.getValue());
            lemmaSet.add(lemma);
            indexSet.add(index);
        }
        lemmaRepository.saveAll(lemmaSet);
        indexRepository.saveAll(indexSet);
        log.info("Леммы и индексы сохранены в БД");

        return true;
    }

    public String getPageTitle (String link) {
        Document document;
        try {
            document = getDocument(link);
        } catch (IOException e) {
            return "";
        }
        return document.title();
    }

    private Page getPageEntity (String link, Site site) {
        Connection.Response connectionResponse;
        Page page = new Page();
        try {
            connectionResponse = getResponse(link);
            if (connectionResponse.statusCode() >= 400) {
                log.error("Страница недоступна.");
                return null;
            }
            page.setSite(site);
            page.setPath(link.substring(link.indexOf("/", link.indexOf("//") + 2)));
            page.setCode(connectionResponse.statusCode());
            page.setContent(connectionResponse.body());
        } catch (IOException e) {
            log.error(e.getMessage() + "  Страница не найдена.");
            return null;
        }
        return page;
    }

    private Lemma getLemmaEntity (String lemma, Site site) {
        Optional<Lemma> optionalLemma = lemmaRepository.findByLemmaAndSite(lemma, site);
        log.info("Лемма ищется в БД");
        if (optionalLemma.isPresent()) {
            Lemma presentLemma = optionalLemma.get();
            presentLemma.setFrequency(presentLemma.getFrequency() + 1);
            log.info("Лемма найдена");
            return presentLemma;
        } else {
            Lemma lemmaEntity = new Lemma();
            lemmaEntity.setSite(site);
            lemmaEntity.setLemma(lemma);
            lemmaEntity.setFrequency(1);
            log.info("Лемма не найдена - формируется новая");
            return lemmaEntity;
        }
    }

    private Index getIndexEntity (Page page, Lemma lemma, Integer rank) {
        Index index = new Index();
        index.setPage(page);
        index.setLemma(lemma);
        index.setRank(rank);
        log.info("Сформирован индекс");
        return index;
    }

    public static Connection.Response getResponse (String link) throws IOException {
        return Jsoup
                .connect(link)
                .timeout(webConnection.getTimeout())
                .userAgent(webConnection.getAgent())
                .referrer(webConnection.getReferrer())
                .execute();
    }

    public static Document getDocument (String link) throws IOException {
        return Jsoup
                .connect(link)
                .timeout(webConnection.getTimeout())
                .userAgent(webConnection.getAgent())
                .referrer(webConnection.getReferrer())
                .get();
    }
}
