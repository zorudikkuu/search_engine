package searchengine.model.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.entities.Lemma;
import searchengine.model.entities.Site;

import java.util.List;
import java.util.Optional;

public interface LemmaRepository extends JpaRepository<Lemma, Integer> {
    Optional<Lemma> findByLemmaAndSite (String lemma, Site site);
    List<Lemma> findByLemma (String lemma);
    List<Lemma> findBySite (Site site);
}
