package searchengine.model.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.entities.Page;

@Repository
public interface PageRepository extends JpaRepository<Page, Integer> {
}
