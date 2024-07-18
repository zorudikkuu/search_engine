package searchengine.services;

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

@Service
public class IndexingServiceImpl implements IndexingService {
    private final SitesList sitesList;
    private ExecutorService executor;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private CountDownLatch latch;
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
        if (siteByUrl != null) {
            Optional<Site> optionalSite = siteRepository.findByUrl(domain);
            site = optionalSite.isPresent() ? optionalSite.get() : saveSiteEntity(siteByUrl, IndexingStatus.INDEXED);
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
        Set<SearchData> dataSet = new HashSet<>();

        for (Map.Entry<Page, Double> pair : pageToRelevance.entrySet()) {
            SearchData dataEntity = new SearchData();
            String siteLink = pair.getKey().getSite().getUrl().replaceAll("/$", "");
            dataEntity.setSite(siteLink);
            dataEntity.setSiteName(pair.getKey().getSite().getName());
            String pagePath = pair.getKey().getPath();
            dataEntity.setUri(pagePath);
            String absHref = siteLink + pagePath;
            dataEntity.setTitle(pageIndexer.getPageTitle(absHref));
            dataEntity.setSnippet(getSnippet(pair.getKey().getContent(), keyWords));
            dataSet.add(dataEntity);

        }
        Set<SearchData> dataPart = dataSet.stream().skip(offset).limit(limit).collect(Collectors.toSet());

        //TODO: -----> taskList                       | priority | level | status
        // 1) реализовать генерацию сниппета          |     6    |   2   | DONE
        // 2) разобраться с параметрами limit и offset|     5    |   1   | DONE
        // 3) оптимизировать индексацию               |     4    |   4   |
        // 4) реализовать обработку английских слов   |     3    |   2   |
        // 5) провести рефакторинг                    |     2    |   1   |
        // 6) написать документацию и тесты           |     1    |   3   |

        response.setResult(true);
        response.setCount(dataSet.size());
        response.setData(dataPart);
        return response;
    }

    private void executeSiteParsing (SiteDto siteDto) {
        Optional<Site> siteOptional = siteRepository.findByUrl(siteDto.getUrl());
        siteOptional.ifPresent(site -> {
            Set<Lemma> lemmasToDelete = site.getLemmaSet();
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

        String snippet = "";
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
                snippet = part;
            }
        }

        return "..." + snippet + "...";
    }

    private HashMap<Page, Double> calculateRelevance (List<Page> pages, Set<Lemma> keyWords) {
        HashMap<Page, Double> pageToAbsoluteRelevance = new HashMap<>();
        HashMap<Page, Double> pageToRelevance = new HashMap<>();
        List<Integer> absoluteRelevanceList = new ArrayList<>();
        int maxAbsoluteRelevance = 0;
        for (Page page : pages) {
            List<Index> indexes = new ArrayList<>();
            for (Lemma lemma : keyWords) {
                Optional<Index> optionalIndex = indexRepository.findByLemmaAndPage(lemma, page);
                optionalIndex.ifPresent(indexes::add);
            }

            List<Float> ranks = indexes.stream().map(Index::getRank).toList();
            int absoluteRelevance = 0;
            for (Float rank : ranks) {
                absoluteRelevance += rank;
            }
            absoluteRelevanceList.add(absoluteRelevance);
            pageToAbsoluteRelevance.put(page, (double) absoluteRelevance);
        }

        for (Integer absoluteRelevance : absoluteRelevanceList) {
            if (absoluteRelevance > maxAbsoluteRelevance) {
                maxAbsoluteRelevance = absoluteRelevance;
            }
        }

        for (Map.Entry<Page, Double> pair : pageToAbsoluteRelevance.entrySet()) {
            Page page = pair.getKey();
            Double relevance = pair.getValue() / maxAbsoluteRelevance;
            pageToRelevance.put(page, relevance);
        }

        return pageToRelevance;
    }

    private List<Page> findPagesByQuery (TreeSet<Lemma> keyWords) {
        if (keyWords.isEmpty()) {
            return new ArrayList<>();
        }

        List<Site> sites = keyWords
                .stream()
                .map(Lemma::getSite)
                .distinct().toList();

        TreeSet<Lemma> allKeyWords = keyWords;
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
                Iterator<Page> iterator = foundPagesForSite.iterator();
                while (iterator.hasNext()) {
                    Page page = iterator.next();
                    boolean isContainsPage = pagesWithLemma
                            .stream()
                            .anyMatch(pageWithLemma -> Objects.equals(pageWithLemma, page));
                    if (!isContainsPage) {
                        iterator.remove();
                    }
                }
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
                if (lemmaExist.getFrequency() < site.getPageSet().size() * 0.8) {
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
            if (totalFrequency.get() < pageRepository.findAll().size() * 0.8) {
                keyWords.addAll(lemmaList);
            }
        });
        return keyWords;
    }

    private List<Page> findPagesByLemma (Lemma lemma) {
        List<Page> foundPages = new ArrayList<>();
        List<Lemma> sameLemmas = lemmaRepository.findByLemma(lemma.getLemma());
        for (Lemma sameLemma : sameLemmas) {
            List<Page> pages = indexRepository
                    .findByLemma(sameLemma)
                    .stream()
                    .map(Index::getPage)
                    .toList();
            foundPages.addAll(pages);
        }
        return foundPages;
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
