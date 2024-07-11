package searchengine.services;

import lombok.extern.log4j.Log4j2;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;
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
    public boolean executePageIndexing (String link, Site site) {
        Page page = getPageEntity(link, site);
        if (page == null) {
            return false;
        }

        pageRepository.save(page);
        HashMap<String, Integer> lemmas = new HashMap<>(textParser.getLemmas(page.getContent()));
        for (Map.Entry<String, Integer> lemmaToCount : lemmas.entrySet()) {
            Lemma lemma = getLemmaEntity(lemmaToCount.getKey(), site);
            Index index = getIndexEntity(page, lemma, lemmaToCount.getValue());
            lemmaRepository.save(lemma);
            indexRepository.save(index);

        }
        return true;
    }

    private Page getPageEntity (String link, Site site) {
        Connection.Response connectionResponse;
        Page page = new Page();
        try {
            connectionResponse = getResponse(link);
            if (connectionResponse.statusCode() >= 400) {
                return null;
            }
            page.setSite(site);
            page.setPath(link.substring(link.indexOf("/", link.indexOf("//") + 2)));
            page.setCode(connectionResponse.statusCode());
            page.setContent(connectionResponse.body());
        } catch (IOException e) {
            System.out.println(e.getMessage() + " Страница не найдена");
            return null;
        }
        return page;
    }

    private Lemma getLemmaEntity (String lemma, Site site) {
        if (lemmaRepository.findByLemmaAndSite(lemma, site).isPresent()) {
            Lemma presentLemma = lemmaRepository.findByLemmaAndSite(lemma, site).get();
            presentLemma.setFrequency(presentLemma.getFrequency() + 1);
            return presentLemma;
        } else {
            Lemma lemmaEntity = new Lemma();
            lemmaEntity.setSite(site);
            lemmaEntity.setLemma(lemma);
            lemmaEntity.setFrequency(1);
            return lemmaEntity;
        }
    }

    private Index getIndexEntity (Page page, Lemma lemma, Integer rank) {
        Index index = new Index();
        index.setPage(page);
        index.setLemma(lemma);
        index.setRank(rank);
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
