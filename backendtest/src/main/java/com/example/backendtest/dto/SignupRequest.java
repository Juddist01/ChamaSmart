package com.example.backendtest.dto;


import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class SignupRequest {
    @NotBlank
    private String fullName;

    @NotBlank
    private String phoneNo;

    @Email(message = "Email must be valid")
    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;

    @NotBlank
    private String chamaName;

    @NotBlank
    private String role;




    // Getters & Setters
    public String getFullName() { return fullName; } // This fixes the error
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
    public void getRole(String role){this.role=role;}


}

