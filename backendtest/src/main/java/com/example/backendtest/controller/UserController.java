package com.example.backendtest.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.example.backendtest.dto.SignupRequest;
import com.example.backendtest.dto.LoginRequest;
import com.example.backendtest.repository.UserRepository;
import com.example.backendtest.model.User;
import java.util.Optional;
import com.example.backendtest.security.JwtUtil;


@RestController
@RequestMapping("/api")
public class UserController {

  
    
    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/users")
    public List<Map<String, String>> getUsers() {
        return List.of(
                Map.of("id", "1", "name", "Alex"),
                Map.of("id", "2", "name", "Jane")
        );
    }

    @PostMapping("/login")
    public Map<String,String> login(@Valid @RequestBody LoginRequest request) {
        // 1. Find the user
        Optional<User> userOptional = userRepository.findByEmail(request.getEmail());

        // 2. Validate existence and password
        if(userOptional.isEmpty() || !userOptional.get().getPassword().trim().equals(request.getPassword().trim())){
            throw new RuntimeException("Invalid credentials");
        }

        // 3. Extract the actual User object from the Optional
        User user = userOptional.get();

        // 4. Generate token and return response
        String token = JwtUtil.generateToken(user.getEmail());

        return Map.of(
                "status", "success",
                "token", token,
                "role", user.getRole()
        );
    }
    @PostMapping("/signup")
    public Map<String, String> signup(@Valid @RequestBody SignupRequest request) {
        // 1. Create a new User object from the request
        User newUser = new User();
        newUser.setFullName(request.getFullName());
        newUser.setPhoneNo(request.getPhoneNo());
        newUser.setEmail(request.getEmail());
        newUser.setPassword(request.getPassword());
        newUser.setChamaName(request.getChamaName());
        newUser.setRole(request.getRole());



        userRepository.save(newUser);

        return Map.of(
                "status", "success",
                "message", "User registered successfully",

                "email", request.getEmail()
        );

    }
}