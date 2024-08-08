package searchengine.services;

import lombok.EqualsAndHashCode;
import org.springframework.stereotype.Service;
import searchengine.config.SitesList;
import searchengine.dto.responses.ErrorSearchResponse;
import searchengine.dto.responses.SearchResponse;
import searchengine.dto.responses.SuccessfulSearchResponse;
import searchengine.dto.search.SearchData;
import searchengine.model.entities.Index;
import searchengine.model.entities.Lemma;
import searchengine.model.entities.Page;
import searchengine.model.entities.Site;
import searchengine.model.repositories.IndexRepository;
import searchengine.model.repositories.LemmaRepository;
import searchengine.model.repositories.PageRepository;
import searchengine.model.repositories.SiteRepository;
import searchengine.utils.Language;
import searchengine.utils.PageIndexer;
import searchengine.utils.TextParser;
import searchengine.utils.TextParserImpl;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@EqualsAndHashCode
public class SearchServiceImpl implements SearchService {
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final PageIndexer pageIndexer;
    private final TextParser textParser;
    public SearchServiceImpl (SiteRepository siteRepository, PageRepository pageRepository,
                                LemmaRepository lemmaRepository, IndexRepository indexRepository,
                                PageIndexer pageIndexer, TextParserImpl textParser
    ) {
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.lemmaRepository = lemmaRepository;
        this.indexRepository = indexRepository;
        this.pageIndexer = pageIndexer;
        this.textParser = textParser;
    }
    @Override
    public SearchResponse search(String query, String siteUrl, int offset, int limit) {
        if (query.isBlank()) {
            return new ErrorSearchResponse("Задан пустой поисковый запрос");
        }
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
            response.setData(new ArrayList<>());
        }
        foundPages.forEach(page -> System.out.println(page.getId() + " - " + page.getPath()));

        HashMap<Page, Double> pageToRelevance = calculateRelevance(foundPages, keyWords);
        List<SearchData> dataList = new ArrayList<>();

        for (Map.Entry<Page, Double> pair : pageToRelevance.entrySet()) {
            String snippet = getSnippet(pair.getKey().getContent(), keyWords);
            SearchData dataEntity = getPageData(pair.getKey(), pair.getValue(), snippet);
            dataList.add(dataEntity);
        }
        dataList.sort(Comparator.comparing(SearchData::getRelevance).reversed());
        List<SearchData> dataPart = dataList.stream().skip(offset).limit(limit).toList();

        response.setResult(true);
        response.setCount(dataList.size());
        response.setData(dataPart);
        return response;
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
        List<String> textParts = decomposeText(content);
        StringBuilder snippet = new StringBuilder();
        long maxCount = 0L;
        for (String part : textParts) {
            long keyCount = 0L;
            List<String> words = Arrays.stream(part.split("[^А-ЯЁа-яёA-Za-z]+")).toList();
            for (Lemma keyWord : keyWords) {
                List<String> keys = findKeyWord(words, keyWord);
                keyCount = keys.size();
                for (String key : keys) {
                    key = key.replaceAll("[^А-ЯЁа-яёA-Za-z]", "");
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

    private List<String> decomposeText (String text) {
        List<String> textParts = new ArrayList<>();
        text = textParser.replaceHtml(text).replaceAll(" +", " ").trim();
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
        return textParts;
    }

    private List<String> findKeyWord (List<String> words, Lemma keyWord) {
        return words
                .stream()
                .filter(word -> {
                    word = word.replaceAll("[^А-ЯЁа-яёA-Za-z]", "");
                    if (word.isBlank()) {
                        return false;
                    }

                    Language language;
                    if (word.matches("^[a-zA-Z]*$")) {
                        language = Language.ENGLISH;
                    } else if (word.matches("^[А-ЯЁа-яё]*$")){
                        language = Language.RUSSIAN;
                    } else {
                        return false;
                    }

                    return textParser.getLemma(word.toLowerCase(), language).equals(keyWord.getLemma());
                })
                .toList();
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
        List<Site> sites = keyWords.stream().map(Lemma::getSite).distinct().toList();
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
                if (lemmaExist.getFrequency() < pageCount * 0.7 || pageCount < 50) {
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
            if (totalFrequency.get() < pageCount * 0.7 || pageCount < 50) {
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
}
