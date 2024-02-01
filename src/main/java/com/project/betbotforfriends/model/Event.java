package com.project.betbotforfriends.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity(name = "events")
public class Event {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer event_id;
    private Integer eventNumber;
    private String homeTeam;
    private String guestTeam;
    private Double homeTeamWinsCoef;
    private Double guestTeamWinsCoef;
    private Double drawCoef;
//    @ManyToOne
//    @JoinColumn(name = "result_id")
    private Integer result_id;
}
