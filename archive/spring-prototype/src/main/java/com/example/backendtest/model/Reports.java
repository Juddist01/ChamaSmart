package com.example.backendtest.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;

@Entity
@Table(name = "reports")
public class Reports {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String chamaName;
    private String reportType; // e.g., "Financial Statement", "Contribution History"
    private String generatedBy; // Admin name
    private String period; // e.g., "January 2026"
    //private LocalDateTime generatedAt = LocalDateTime.now();

    // In a real app, this would be a URL to an S3 bucket or a file path
    private String fileUrl;
}
