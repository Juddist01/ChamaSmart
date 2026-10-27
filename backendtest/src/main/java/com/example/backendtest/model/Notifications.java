package com.example.backendtest.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;

@Entity
@Table(name = "notifications")
public class Notifications {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email; // The user who should see this
    private String title;
    private String message;
    private boolean isRead = false;
   // private LocalDateTime createdAt = LocalDateTime.now();

    // Standard Getters and Setters...
}