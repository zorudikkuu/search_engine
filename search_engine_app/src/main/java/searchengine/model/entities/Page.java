package searchengine.model.entities;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import javax.persistence.Index;
import java.util.Set;

@Entity
@Table(name = "page", indexes = @Index(columnList = "path", unique = true))
@Getter
@Setter
public class Page {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @JoinColumn(name = "site_id", referencedColumnName = "id", nullable = false)
    @ManyToOne
    private Site site;

    @Column(name = "path", nullable = false, columnDefinition = "TEXT")
    private String path;

    @Column(name = "code", nullable = false)
    private int code;

    @Column(name = "content", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @OneToMany(mappedBy = "page", targetEntity = searchengine.model.entities.Index.class, cascade = CascadeType.ALL)
    private Set<searchengine.model.entities.Index> IndexSet;
}
