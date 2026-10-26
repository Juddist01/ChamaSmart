package com.example.backendtest.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "chama")
public class Chama {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    private String description;

    private BigDecimal totalSavings = BigDecimal.ZERO;

    private BigDecimal monthlyContributionAmount;

    private String adminEmail; // To link the creator/admin

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getTotalSavings() { return totalSavings; }
    public void setTotalSavings(BigDecimal totalSavings) { this.totalSavings = totalSavings; }
    public BigDecimal getMonthlyContributionAmount() { return monthlyContributionAmount; }
    public void setMonthlyContributionAmount(BigDecimal amount) { this.monthlyContributionAmount = amount; }
}