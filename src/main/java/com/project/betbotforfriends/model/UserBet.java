package com.project.betbotforfriends.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity(name = "user_bets")
public class UserBet {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;
//    @ManyToOne
//    @JoinColumn(name = "user_id")
    private Long user_id;
//    @ManyToOne
//    @JoinColumn(name = "event_id")
    private Integer event_id;
//    @ManyToOne
//    @JoinColumn(name = "bet_id")
    private Integer bet_id;
}
