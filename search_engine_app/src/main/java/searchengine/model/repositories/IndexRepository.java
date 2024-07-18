package searchengine.model.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.entities.Index;
import searchengine.model.entities.Lemma;
import searchengine.model.entities.Page;

import java.util.List;
import java.util.Optional;

public interface IndexRepository extends JpaRepository<Index, Integer> {
    List<Index> findByLemma (Lemma lemma);
    Optional<Index> findByLemmaAndPage (Lemma lemma, Page page);
}
