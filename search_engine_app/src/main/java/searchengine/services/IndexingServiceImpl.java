package searchengine.services;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import searchengine.config.SiteDto;
import searchengine.config.SitesList;
import searchengine.dto.responses.IndexingResponse;
import searchengine.dto.responses.SuccessfulSearchResponse;
import searchengine.dto.search.SearchData;
import searchengine.model.IndexingStatus;
import searchengine.model.entities.Index;
import searchengine.model.entities.Lemma;
import searchengine.model.entities.Page;
import searchengine.model.entities.Site;
import searchengine.model.repositories.IndexRepository;
import searchengine.model.repositories.LemmaRepository;
import searchengine.model.repositories.PageRepository;
import searchengine.model.repositories.SiteRepository;
import searchengine.utils.PageIndexer;
import searchengine.utils.TextParser;
import searchengine.utils.TextParserImpl;
import searchengine.utils.WebParserTask;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Log4j2
@Service
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

    //TODO: -----> taskList                       | priority | level | status
    // 1) реализовать обработку английских слов   |     3    |   2   |
    // 2) провести рефакторинг                    |     1    |   1   | IN PROGRESS
    // 3) пофиксить выдачу результатов по 1 сайту |     2    |   2   | DONE


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

        for (SiteDto siteDto : sites) {
            executor.submit(() -> executeSiteParsing(siteDto));
        }

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
                log.info("ForkJoinPool terminated");
            }
        } catch (InterruptedException e) {
            log.error("Termination failed");
        }
        executor.shutdownNow();
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
        } catch (NullPointerException e) {
            response.setResult(false);
            response.setError("Данная страница находится за " +
                              "пределами сайтов, указанных в " +
                              "конфигурационном файле");
            return response;
        }

        Optional<Page> optionalPage = pageRepository.findByPath(absHref.substring(endDomainIndex));
        optionalPage.ifPresent(this::clearPageInfo);

        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            pageIndexer.executePageIndexing(absHref, site);
        });
        response.setResult(true);
        return response;
    }

    @Override
    public SuccessfulSearchResponse search(String query, String siteUrl, int offset, int limit) {
        SuccessfulSearchResponse response = new SuccessfulSearchResponse();

        Optional<Site> optionalSite = siteRepository.findByUrl(siteUrl);
        Set<String> queryLemmas = textParser.getLemmas(query).keySet();
        TreeSet<Lemma> keyWords = optionalSite
                .map(site -> getQueryKeyWords(queryLemmas, site))
                .orElseGet(() -> getQueryKeyWords(queryLemmas));

        List<Page> foundPages = findPagesByQuery(keyWords);
        if (foundPages.isEmpty()) {
            response.setResult(true);
            response.setCount(0);
            response.setData(new HashSet<>());
        }

        HashMap<Page, Double> pageToRelevance = calculateRelevance(foundPages, keyWords);
        Set<SearchData> dataSet = new TreeSet<>(Comparator.comparing(SearchData::getRelevance));

        for (Map.Entry<Page, Double> pair : pageToRelevance.entrySet()) {
            String snippet = getSnippet(pair.getKey().getContent(), keyWords);
            SearchData dataEntity = getPageData(pair.getKey(), pair.getValue(), snippet);
            dataSet.add(dataEntity);
        }
        Set<SearchData> dataPart = dataSet.stream().skip(offset).limit(limit).collect(Collectors.toSet());

        response.setResult(true);
        response.setCount(dataSet.size());
        response.setData(dataPart);
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
            indexingSite.setIndexingStatus(IndexingStatus.INDEXED);
            indexingSite.setStatusTime(LocalDateTime.now());
            siteRepository.save(indexingSite);
            log.info("Сайт успешно проиндексирован");
        }
    }

    private SearchData getPageData (Page page, Double relevance, String snippet) {
        SearchData dataEntity = new SearchData();
        String siteLink = page.getSite().getUrl().replaceAll("/$", "");
        dataEntity.setSite(siteLink);
        dataEntity.setSiteName(page.getSite().getName());
        String pagePath = page.getPath();
        dataEntity.setUri(pagePath);
        String absHref = siteLink + pagePath;
        dataEntity.setTitle(pageIndexer.getPageTitle(absHref));
        dataEntity.setSnippet(snippet);
        dataEntity.setRelevance(relevance);
        return dataEntity;
    }

    private String getSnippet (String content, Set<Lemma> keyWords) {

        List<String> textParts = new ArrayList<>();
        String text = textParser.replaceHtml(content).replaceAll(" +", " ").trim();
        int startIndex = 0;
        int endIndex = 300;

        while (endIndex < text.length()) {
            textParts.add(text.substring(startIndex, endIndex + 1));
            startIndex = startIndex + 300;
            endIndex = endIndex + 300;
        }
        if (text.length() <= 300) {
            textParts.add(text);
        } else {
            textParts.add(text.substring(endIndex - 300));
        }

        StringBuilder snippet = new StringBuilder();
        long maxCount = 0L;
        for (String part : textParts) {
            long keyCount = 0L;
            List<String> words = Arrays.stream(part.split("\\s+")).toList();
            for (Lemma keyWord : keyWords) {

                List<String> keys = words
                        .stream()
                        .filter(word -> {
                            word = word.replaceAll("[^А-Яа-я]", "");
                            if (word.isBlank()) {
                                return false;
                            }
                            return textParser.getLemma(word.toLowerCase()).equals(keyWord.getLemma());
                        })
                        .toList();
                keyCount = keys.size();
                for (String key : keys) {
                    key = key.replaceAll("[^А-Яа-я]", "");
                    part = part.replaceAll(key, "<b>" + key + "</b>");
                }
            }
            if (keyCount > maxCount) {
                maxCount = keyCount;
                snippet = new StringBuilder(part);
            }
        }
        snippet = new StringBuilder().append("...").append(snippet).append("...");

        return snippet.toString();
    }

    private HashMap<Page, Double> calculateRelevance (List<Page> pages, Set<Lemma> keyWords) {
        HashMap<Page, Double> pageToAbsoluteRelevance = new HashMap<>();
        HashMap<Page, Double> pageToRelevance = new HashMap<>();
        List<Double> absoluteRelevanceList = new ArrayList<>();
        Double maxAbsoluteRelevance = 0.0;

        for (Page page : pages) {
            double absoluteRelevance = getAbsoluteRelevance(page, keyWords);
            absoluteRelevanceList.add(absoluteRelevance);
            pageToAbsoluteRelevance.put(page, absoluteRelevance);
        }

        for (Double absoluteRelevance : absoluteRelevanceList) {
            maxAbsoluteRelevance = Double.max(absoluteRelevance, maxAbsoluteRelevance);
        }

        for (Map.Entry<Page, Double> pair : pageToAbsoluteRelevance.entrySet()) {
            Page page = pair.getKey();
            Double relevance = pair.getValue() / maxAbsoluteRelevance;
            pageToRelevance.put(page, relevance);
        }

        return pageToRelevance;
    }

    private Double getAbsoluteRelevance (Page page, Set<Lemma> keyWords) {
        List<Index> indexes = new ArrayList<>();
        for (Lemma lemma : keyWords) {
            Optional<Index> optionalIndex = indexRepository.findByLemmaAndPage(lemma, page);
            optionalIndex.ifPresent(indexes::add);
        }

        List<Float> ranks = indexes.stream().map(Index::getRank).toList();
        double absoluteRelevance = 0;
        for (Float rank : ranks) {
            absoluteRelevance += rank;
        }
        return absoluteRelevance;
    }

    private List<Page> findPagesByQuery (Set<Lemma> keyWords) {
        if (keyWords.isEmpty()) {
            return new ArrayList<>();
        }

        List<Site> sites = keyWords
                .stream()
                .map(Lemma::getSite)
                .distinct()
                .toList();

        keyWords = new TreeSet<>(keyWords);
        TreeSet<Lemma> allKeyWords = (TreeSet<Lemma>) keyWords;
        List<Page> foundPages = new ArrayList<>();
        for (Site site : sites) {
            keyWords = allKeyWords
                    .stream()
                    .filter(lemma -> lemma.getSite().equals(site))
                    .collect(Collectors.toCollection(TreeSet::new));
            if (keyWords.isEmpty()) {
                continue;
            }
            Lemma rarestWord = keyWords.stream().findFirst().get();
            List<Page> foundPagesForSite = findPagesByLemma(rarestWord);
            for (Lemma lemma : keyWords) {
                List<Page> pagesWithLemma = findPagesByLemma(lemma);
                foundPagesForSite = foundPagesForSite
                        .stream()
                        .filter(page -> pagesWithLemma
                                .stream()
                                .anyMatch(pageWithLemma -> Objects.equals(pageWithLemma, page)))
                        .toList();
            }
            foundPages.addAll(foundPagesForSite);
        }
        return foundPages;
    }

    private TreeSet<Lemma> getQueryKeyWords (Set<String> lemmas, Site site) {
        TreeSet<Lemma> keyWords = new TreeSet<>();
        lemmas.forEach(lemma -> {
            Optional<Lemma> optionalLemma = lemmaRepository.findByLemmaAndSite(lemma, site);
            if (optionalLemma.isPresent()) {
                Lemma lemmaExist = optionalLemma.get();
                int pageCount = site.getPageSet().size();
                if (lemmaExist.getFrequency() < pageCount * 0.8 || pageCount < 100) {
                    keyWords.add(lemmaExist);
                }
            }
        });
        return keyWords;
    }

    private TreeSet<Lemma> getQueryKeyWords (Set<String> lemmas) {
        TreeSet<Lemma> keyWords = new TreeSet<>();
        lemmas.forEach(lemma -> {
            List<Lemma> lemmaList = lemmaRepository.findByLemma(lemma);
            AtomicInteger totalFrequency = new AtomicInteger();
            lemmaList.forEach(lemma1 -> totalFrequency.set(totalFrequency.get() + lemma1.getFrequency()));
            int pageCount = pageRepository.findAll().size();
            if (totalFrequency.get() < pageCount * 0.8 || pageCount < 100) {
                keyWords.addAll(lemmaList);
            }
        });
        return keyWords;
    }

    private List<Page> findPagesByLemma (Lemma lemma) {
        return indexRepository
                .findByLemma(lemma)
                .stream()
                .map(Index::getPage)
                .toList();
    }

    private Site findSiteInConfiguration (String link) throws NullPointerException {
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
            throw new NullPointerException();
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

    private boolean isIndexing (List<Site> sites) {
        return sites
                .stream()
                .map(Site::getIndexingStatus)
                .anyMatch(status -> status.equals(IndexingStatus.INDEXING));
    }
}
