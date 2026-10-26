package com.example.backendtest.model;

import jakarta.persistence.*;

@Entity
@Table(name = "contributions")
public class Contributions {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String userName;
    private String chamaName;
    private Double amountPaid;
    //private LocalDate dueDate; // Set to 5th of month
    private String status; // PAID, LATE, PENDING
    private String paymentMethod;
}
