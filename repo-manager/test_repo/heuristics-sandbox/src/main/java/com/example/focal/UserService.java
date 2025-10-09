package com.example.focal;

public class UserService {
    private final UserRepository repo;

    public UserService(UserRepository repo) {
        this.repo = repo;
    }

    public boolean register(String email) {
        if (email == null || email.isBlank())
            throw new IllegalArgumentException("email");
        if (repo.exists(email))
            return false;
        repo.save(email);
        return true;
    }
}
