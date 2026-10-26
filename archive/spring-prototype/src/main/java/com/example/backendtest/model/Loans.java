package com.example.backendtest.model;

import jakarta.persistence.*;


@Entity
@Table(name = "loans")
public class Loans {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String userName;
    private Double amount;
    private Double interestRate = 5.0; // 5% p.a.
    private Integer durationMonths;
    private Double totalToPay;
    private String status; // PENDING, ACCEPTED, DENIED
    private String repaymentStatus; // PENDING, COMPLETED
}
