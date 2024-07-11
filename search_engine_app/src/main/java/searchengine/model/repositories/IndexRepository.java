package searchengine.model.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.entities.Index;

public interface IndexRepository extends JpaRepository<Index, Integer> {
}
