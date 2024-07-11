package searchengine.model.entities;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.util.Set;

@Entity
@Table(name = "lemma")
@Getter
@Setter
public class Lemma {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @JoinColumn(name = "site_id", referencedColumnName = "id", nullable = false)
    @ManyToOne
    private Site site;

    @Column(name = "lemma", columnDefinition = "VARCHAR(255)", nullable = false)
    private String lemma;

    @Column(name = "frequency", nullable = false)
    private int frequency;

    @OneToMany(mappedBy = "lemma", targetEntity = Index.class, cascade = CascadeType.ALL)
    private Set<Index> indexSet;
}
