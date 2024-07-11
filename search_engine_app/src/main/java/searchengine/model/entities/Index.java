package searchengine.model.entities;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

@Entity
@Table(name = "1ndex") //"\"index\""
@Getter
@Setter
public class Index {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @JoinColumn(name = "page_id", referencedColumnName = "id", nullable = false)
    @ManyToOne
    private Page page;

    @JoinColumn(name = "lemma_id", referencedColumnName = "id", nullable = false)
    @ManyToOne
    private Lemma lemma;

    @Column(name = "\"rank\"", nullable = false)
    private float rank;
}
