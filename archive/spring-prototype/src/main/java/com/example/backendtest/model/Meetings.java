package com.example.backendtest.model;

import jakarta.persistence.*;

@Entity
@Table(name = "meetings")
public class Meetings {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
   // private LocalDateTime meetingDate;
    private String location;
    @Column(columnDefinition = "TEXT")
    private String agenda;
    @Column(columnDefinition = "TEXT")
    private String minutes;
}
