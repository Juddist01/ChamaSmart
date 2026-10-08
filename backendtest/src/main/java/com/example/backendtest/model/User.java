package com.example.backendtest.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;



@Entity
@Table(name = "user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message="Name Required")
    @Column(name="fullname", nullable = false)
    private String fullName;

    @NotBlank(message="Phone number Required")
    @Column(name="phoneno", nullable = false)
    private String phoneNo;

    @NotBlank(message="Email Required")
    @Column(name="email", nullable = false)
    private String email;

    @NotBlank(message="Password Required")
    @Column(name="password", nullable = false)
    private String password;

    @NotBlank(message="Enter your chama's name")
    @Column(name="chama_name", nullable = false)
    private String chamaName;

    @NotBlank(message="Enter your role")
    @Column(name="role", nullable = false)
    private String role;

    // Constructors
    public User() {}
    public User(String fullName, String phoneNo, String email, String password, String chama_name, String role) {
        this.fullName = fullName;
        this.phoneNo = phoneNo;
        this.email = email;
        this.password = password;
        this.chamaName = chamaName;
        this.role = role;


    }

    // Getters & Setters
    public Long getId() { return id; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getPhoneNo() { return phoneNo; } // This fixes the error
    public void setPhoneNo(String phoneNo) { this.phoneNo = phoneNo; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getChamaName() { return chamaName; }
    public void setChamaName(String chamaName) { this.chamaName = chamaName; }

    public String getRole(){return role;}
    public void setRole(String role){this.role=role;}
   }
