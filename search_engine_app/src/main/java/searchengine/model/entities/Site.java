package searchengine.model.entities;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import searchengine.model.IndexingStatus;
import searchengine.model.entities.Page;

import javax.persistence.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Set;

@Entity
@Table(name = "site")
@Getter
@Setter
public class Site {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "ENUM('INDEXING', 'INDEXED', 'FAILED')")
    private IndexingStatus indexingStatus = IndexingStatus.FAILED;

    @Column(name = "status_time", nullable = false)
    private LocalDateTime statusTime;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "url", nullable = false, columnDefinition = "VARCHAR(255)")
    private String url;

    @Column(name = "name", nullable = false, columnDefinition = "VARCHAR(255)")
    private String name;

    @OneToMany(mappedBy = "site", targetEntity = Page.class, cascade = CascadeType.ALL)
    private Set<Page> pageSet;

    @OneToMany(mappedBy = "site", targetEntity = Lemma.class, cascade = CascadeType.ALL)
    private Set<Lemma> lemmaSet;
}
