package com.example.backendtest.model;

import jakarta.persistence.*;


@Entity
@Table(name = "transactions")

public class Transactions {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long user_id;
    private Long chama_id;
    private String type;
    private Double amount;
    private Long reference_id;
    private String description;

}

